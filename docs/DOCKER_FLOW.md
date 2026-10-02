# Docker flow

Untrusted repository code (its tests and its Maven plugins) only ever runs inside disposable containers. On the host, StackTrial only runs `git` and parses files. Two classes do the work:

- `execution/MavenRunExecutor.java` decides **what** runs (the same script for baseline and trial).
- `execution/DockerContainerRunner.java` decides **how** it runs (limits, copy in/out, timeout, cleanup). It is the only class that talks to Docker, through docker-java and the `ContainerRunner` interface.

## One run, step by step

```text
MavenRunExecutor.run(trialId, side, workspaceDir, image)
  └─ ContainerRunSpec(name, image, ["sh","-c",SCRIPT], workspace, labels, collect "/workspace/.stacktrial")
       └─ DockerContainerRunner.run(spec)
            1 ensureImage            inspect image; pull it if missing
            2 cache volume           named volume stacktrial-m2-cache at /m2; chowned to 1000:1000 once
            3 create container       user 1000:1000, limits, CapDrop ALL, no-new-privileges, not privileged
            4 copy workspace in      tar of the workspace (owned by uid 1000; .git and symlinks excluded)
            5 start
            6 wait with timeout      awaitStatusCode(timeout); on timeout → kill, mark TIMEOUT, exit -1
            7 read console log       capped at 200 000 chars
            8 copy results out       tar of /workspace/.stacktrial → data/collected/{trial}-{side}/
            9 finally: remove        removeContainer(force=true, removeVolumes=true)
```

The in-container script (`MavenRunExecutor.SCRIPT`):

```sh
mkdir -p /workspace/.stacktrial/surefire-reports
cd /workspace
mvn -B -ntp -Dstyle.color=never -Dmaven.repo.local=/m2/repository verify > .stacktrial/build.log 2>&1
rc=$?
find . -path ./.stacktrial -prune -o -path '*/target/surefire-reports/TEST-*.xml' -exec cp -t .stacktrial/surefire-reports/ {} +
mvn … -q dependency:tree -DoutputFile=/workspace/.stacktrial/dependency-tree.txt -DappendOutput=true > .stacktrial/dependency-tree.log 2>&1
echo "stacktrial: mvn verify exited with $rc"
exit $rc
```

The container's exit code is the exit code of `mvn verify`. Back on the host, the log is redacted, stored under `data/artifacts/trials/{id}/{SIDE}/`, and parsed (see [EVIDENCE_ENGINE.md](EVIDENCE_ENGINE.md)).

## Baseline and trial are equivalent

| Setting | Source | Same for both sides? |
|---|---|---|
| Memory / swap, CPUs, PIDs | `stacktrial.runner.memory-mb`, `cpus`, `pids-limit` | yes (global config) |
| User, capabilities, privileges | hard-coded + `runner.user` | yes |
| Network | `runner.network-mode` | yes |
| Timeout | `runner.timeout` (`STACKTRIAL_RUN_TIMEOUT`) | yes |
| Command | `MavenRunExecutor.SCRIPT` | yes |
| Maven cache | volume `stacktrial-m2-cache` | yes |
| Image | `RunnerImageResolver` from **that side's** `pom.xml` Java version | differs only if the change is a Java version |
| Workspace | baseline copy vs. trial copy | differs: this is the change under test |

`ContainerRunSpec` deliberately has no limit fields, so there is no way to give one side different resources.

## Security controls

| Control | How | Verified by |
|---|---|---|
| Not privileged | `withPrivileged(false)` | `DockerContainerRunnerIT` |
| Non-root | `withUser("1000:1000")` | IT reads `id -u` = `1000` inside the container |
| No capabilities | `withCapDrop(ALL)` | IT reads `CapEff: 0000000000000000` from `/proc/self/status` |
| No privilege escalation | `no-new-privileges` | IT reads `NoNewPrivs: 1` |
| Memory limit, no extra swap | `withMemory` + `withMemorySwap` (equal) | IT reads the cgroup memory limit (512 MB in the test) |
| Process limit | `withPidsLimit` | IT reads the cgroup `pids.max` (64 in the test) |
| CPU limit | `withNanoCPUs` | configured; not read back in a test |
| Hard timeout | `awaitStatusCode(timeout)` then `kill` | IT: `sleep 60` with a 3 s timeout → `timedOut=true`, exit `-1` |
| Cleanup | `removeContainer(force)` in `finally` | IT: no container with the test label remains; live run: 8 `create` / 8 `destroy` events ([docker-events.log](scenario-results/docker-events.log)) |
| No host mounts of the workspace | copy in/out through the Engine API | IT: file content copied in and out; `.git` absent inside the container |
| No Docker socket in the container | only the cache volume and optional read-only `extra-binds` are mounted | code review (`hostConfig()`) |
| Path traversal on copy-out | entries resolving outside the target directory are skipped | code review (`copyResultsOut`) |
| Workspace cleanup | `WorkspaceService.cleanup` + `discardCollected` in the orchestrator's `finally` | after the live scenarios `data/workspaces/` was empty |

## Configuration

```yaml
stacktrial:
  runner:
    images: {17: maven:3.9-eclipse-temurin-17, 21: maven:3.9-eclipse-temurin-21}
    default-java-version: 21      # used when the pom declares no Java version
    memory-mb: 2048
    cpus: 2.0
    pids-limit: 512
    timeout: 15m                  # STACKTRIAL_RUN_TIMEOUT
    user: "1000:1000"
    network-mode: bridge          # STACKTRIAL_RUNNER_NETWORK
    dependency-cache-volume: stacktrial-m2-cache
    extra-env: {}                 # e.g. MAVEN_OPTS for a proxy
    extra-binds: []               # always mounted read-only
```

The backend connects to Docker through `DOCKER_HOST` (default `unix:///var/run/docker.sock`), see `execution/DockerConfiguration.java`.

## Known trade-offs

- **Network during the build.** Maven needs to download dependencies, so runner containers have network access for the whole run. Two-phase execution (resolve with network, build/test with `--network none`) is a listed next step.
- **Shared Maven cache.** One volume makes repeated runs fast (about 10 s per side for the fixtures) but a malicious build could poison it for later runs. A per-trial cache copy would fix this at the cost of speed.
- **Docker socket on the backend.** The backend itself needs Docker access, which is root-equivalent on the host. Acceptable for a single-user tool bound to `127.0.0.1`; a shared service would run execution on separate worker hosts.
- **Image pulls.** The first trial on a machine pulls `maven:3.9-eclipse-temurin-17/21` (about 250 MB each) and downloads Maven dependencies, so it is much slower than later trials.
