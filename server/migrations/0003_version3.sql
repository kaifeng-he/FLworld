ALTER TABLE personas ADD COLUMN bubble_color TEXT NOT NULL DEFAULT '#FFE0A8';

UPDATE personas
SET bubble_color = '#FFE0A8'
WHERE bubble_color IS NULL OR bubble_color = '';
