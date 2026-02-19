/**
 * api.js — Backend API communication layer.
 * Attaches the JWT access token to all protected requests.
 */

const BASE_URL = '/api/lecture';

/**
 * Upload a PDF and receive an AI-generated summary.
 * Requires a valid JWT access token (from AuthContext).
 *
 * @param {File}   file        - The PDF file to upload.
 * @param {string} accessToken - JWT access token from AuthContext.
 */
export async function uploadLectureForSummary(file, accessToken) {
  const formData = new FormData();
  formData.append('file', file);

  const response = await fetch(`${BASE_URL}/summarize`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${accessToken}`,
      // Do NOT set Content-Type — browser sets multipart boundary automatically
    },
    body: formData,
  });

  const data = await response.json();

  if (!response.ok) {
    const errorMessage = data?.error || `Request failed with status ${response.status}`;
    throw new Error(errorMessage);
  }

  return data;
}

/**
 * Health check (public endpoint — no token required).
 */
export async function checkHealth() {
  const response = await fetch(`${BASE_URL}/health`);
  if (!response.ok) throw new Error('Backend service is unavailable.');
  return response.json();
}