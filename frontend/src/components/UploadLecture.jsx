import { useState, useRef } from 'react';
import { uploadLectureForSummary } from '../services/api';

const MAX_FILE_SIZE_MB = 10;
const ACCEPTED_TYPE    = 'application/pdf';

/**
 * UploadLecture component
 * Handles file selection (click + drag-and-drop), client-side validation,
 * upload progress indication, and result passing to the parent via onSummaryReady callback.
 */
function UploadLecture({ onSummaryReady, onLoading }) {
  const [selectedFile, setSelectedFile] = useState(null);
  const [error,        setError]        = useState('');
  const [loading,      setLoading]      = useState(false);
  const [dragOver,     setDragOver]     = useState(false);
  const inputRef = useRef(null);

  // ─── Validation ───────────────────────────────────────────────────────────

  const validateFile = (file) => {
    if (!file) return 'No file selected.';
    if (file.type !== ACCEPTED_TYPE && !file.name.toLowerCase().endsWith('.pdf')) {
      return 'Only PDF files are supported.';
    }
    if (file.size > MAX_FILE_SIZE_MB * 1024 * 1024) {
      return `File size must not exceed ${MAX_FILE_SIZE_MB}MB. Your file: ${(file.size / 1024 / 1024).toFixed(1)}MB`;
    }
    return null;
  };

  const handleFileChange = (file) => {
    setError('');
    const validationError = validateFile(file);
    if (validationError) {
      setError(validationError);
      setSelectedFile(null);
      return;
    }
    setSelectedFile(file);
  };

  // ─── Drag and Drop ────────────────────────────────────────────────────────

  const handleDragOver  = (e) => { e.preventDefault(); setDragOver(true); };
  const handleDragLeave = ()  => setDragOver(false);
  const handleDrop      = (e) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files[0];
    if (file) handleFileChange(file);
  };

  // ─── Submit ───────────────────────────────────────────────────────────────

  const handleSubmit = async () => {
    if (!selectedFile) {
      setError('Please select a PDF file before submitting.');
      return;
    }

    setLoading(true);
    setError('');
    onLoading(true);

    try {
      const summary = await uploadLectureForSummary(selectedFile);
      onSummaryReady(summary);
    } catch (err) {
      setError(err.message || 'An unexpected error occurred. Please try again.');
    } finally {
      setLoading(false);
      onLoading(false);
    }
  };

  // ─── Render ───────────────────────────────────────────────────────────────

  return (
    <div className="upload-section">
      <div
        className={`upload-zone ${dragOver ? 'drag-over' : ''}`}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={() => inputRef.current?.click()}
      >
        <input
          ref={inputRef}
          type="file"
          accept=".pdf,application/pdf"
          className="file-input-hidden"
          onChange={(e) => handleFileChange(e.target.files[0])}
          onClick={(e) => e.stopPropagation()}
        />
        <div className="upload-icon">📄</div>
        <h3>{dragOver ? 'Drop your PDF here!' : 'Drag & drop your lecture PDF'}</h3>
        <p>or click anywhere in this box to browse files</p>
        <p>PDF only · Maximum 10 MB</p>

        {selectedFile && (
          <div className="file-selected" onClick={(e) => e.stopPropagation()}>
            ✅ <strong>{selectedFile.name}</strong>
            &nbsp;({(selectedFile.size / 1024).toFixed(0)} KB)
          </div>
        )}
      </div>

      {error && (
        <div className="error-banner">
          <span>⚠️</span>
          <span>{error}</span>
        </div>
      )}

      <button
        className="btn btn-primary"
        onClick={handleSubmit}
        disabled={loading || !selectedFile}
      >
        {loading ? '⏳ Processing…' : '🚀 Generate Summary'}
      </button>
    </div>
  );
}

export default UploadLecture;