/**
 * SummaryView component
 * Renders the structured AI-generated lecture summary returned by the backend.
 * Displays: Title, Key Concepts, Definitions, Exam Points, and metadata.
 */

function SummaryView({ summary, onReset }) {
  const { title, keyPoints = [], definitions = [], examPoints = [], fileName, provider, generatedAt, pageCount } = summary;

  const formatDate = (isoString) => {
    if (!isoString) return '';
    try {
      return new Date(isoString).toLocaleString('en-IN', {
        day: '2-digit', month: 'short', year: 'numeric',
        hour: '2-digit', minute: '2-digit'
      });
    } catch { return isoString; }
  };

  const ProviderIcon = { openai: '🤖', claude: '🧠', gemini: '✨' };
  const icon = ProviderIcon[provider?.toLowerCase()] || '🤖';

  return (
    <div className="summary-section">
      {/* Header row */}
      <div className="summary-header">
        <div className="summary-title-group">
          <h2>📚 {title}</h2>
          <div className="summary-meta">
            <span className="meta-tag">📄 {fileName}</span>
            {pageCount > 0 && <span className="meta-tag">📖 {pageCount} page{pageCount !== 1 ? 's' : ''}</span>}
            <span className="meta-tag">{icon} {provider ? provider.charAt(0).toUpperCase() + provider.slice(1) : 'AI'}</span>
            {generatedAt && <span className="meta-tag">🕐 {formatDate(generatedAt)}</span>}
          </div>
        </div>
        <button className="btn-reset" onClick={onReset}>
          ← New Upload
        </button>
      </div>

      {/* Summary Cards */}
      <div className="summary-grid">

        {/* Key Concepts */}
        <div className="summary-card">
          <div className="summary-card-header key-concepts">
            💡 Key Concepts <CountBadge count={keyPoints.length} />
          </div>
          <div className="summary-card-body">
            <ItemList items={keyPoints} bulletClass="bullet-primary" emptyMsg="No key concepts extracted." />
          </div>
        </div>

        {/* Definitions */}
        <div className="summary-card">
          <div className="summary-card-header definitions">
            📖 Important Definitions <CountBadge count={definitions.length} />
          </div>
          <div className="summary-card-body">
            <ItemList items={definitions} bulletClass="bullet-warning" emptyMsg="No definitions extracted." />
          </div>
        </div>

        {/* Exam Points */}
        <div className="summary-card">
          <div className="summary-card-header exam-points">
            🎯 Exam-Focused Takeaways <CountBadge count={examPoints.length} />
          </div>
          <div className="summary-card-body">
            <ItemList items={examPoints} bulletClass="bullet-success" emptyMsg="No exam points extracted." />
          </div>
        </div>

      </div>
    </div>
  );
}

// ─── Sub-components ──────────────────────────────────────────────────────────

function CountBadge({ count }) {
  if (!count) return null;
  return (
    <span style={{
      marginLeft: 'auto',
      background: 'rgba(0,0,0,0.10)',
      borderRadius: '100px',
      padding: '0.15rem 0.6rem',
      fontSize: '0.75rem',
      fontWeight: '700'
    }}>
      {count}
    </span>
  );
}

function ItemList({ items, bulletClass, emptyMsg }) {
  if (!items || items.length === 0) {
    return <p className="empty-list">{emptyMsg}</p>;
  }
  return (
    <ul className="summary-list">
      {items.map((item, idx) => (
        <li key={idx}>
          <span className={`list-bullet ${bulletClass}`}>{idx + 1}</span>
          <span>{item}</span>
        </li>
      ))}
    </ul>
  );
}

export default SummaryView;