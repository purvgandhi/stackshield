-- V3: Add analysis_result JSONB column for Codebase Analyzer
ALTER TABLE imported_repository ADD COLUMN IF NOT EXISTS analysis_result JSONB;
