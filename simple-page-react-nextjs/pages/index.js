import { useState, useRef, useCallback, useEffect } from 'react';
import Head from 'next/head';

// ── 상수 ──────────────────────────────────────────────────────────────────────

const SIDEBAR_MIN = 160;
const SIDEBAR_MAX = 420;
const SIDEBAR_DEFAULT = 260;

const BACKENDS = [
  { label: 'Spring Boot Maven',  port: 9201, tag: 'Spring',  color: '#6db33f' },
  { label: 'Spring Boot Gradle', port: 9202, tag: 'Spring',  color: '#6db33f' },
  { label: 'Node.js NestJS',     port: 3201, tag: 'NestJS',  color: '#e0234e' },
  { label: 'Node.js Express',    port: 3202, tag: 'Express', color: '#303030' },
  { label: 'Node.js Fastify',    port: 3203, tag: 'Fastify', color: '#00b4d8' },
  { label: 'Python Flask',       port: 5201, tag: 'Flask',   color: '#3c78d8' },
  { label: 'Python FastAPI',     port: 5202, tag: 'FastAPI', color: '#009688' },
];

const ENDPOINTS = [
  { key: 'getAll',  method: 'GET',    path: '/api/items',     summary: '전체 조회', pathParams: [],    bodyFields: null },
  { key: 'getOne',  method: 'GET',    path: '/api/items/{id}',summary: '단건 조회', pathParams: ['id'],bodyFields: null },
  { key: 'create',  method: 'POST',   path: '/api/items',     summary: '등록',      pathParams: [],    bodyFields: ['name','description','price'] },
  { key: 'update',  method: 'PUT',    path: '/api/items/{id}',summary: '수정',      pathParams: ['id'],bodyFields: ['name','description','price'] },
  { key: 'delete',  method: 'DELETE', path: '/api/items/{id}',summary: '삭제',      pathParams: ['id'],bodyFields: null },
];

const METHOD_COLOR = { GET: '#61affe', POST: '#49cc90', PUT: '#fca130', DELETE: '#f93e3e' };

// ── 유틸 ──────────────────────────────────────────────────────────────────────

function buildUrl(backend, path, params) {
  let url = path;
  for (const [k, v] of Object.entries(params)) url = url.replace(`{${k}}`, encodeURIComponent(v));
  return `http://localhost:${backend.port}${url}`;
}

// ── EndpointCard ──────────────────────────────────────────────────────────────

function EndpointCard({ ep, backend }) {
  const [open,    setOpen]    = useState(false);
  const [inputs,  setInputs]  = useState({});
  const [loading, setLoading] = useState(false);
  const [result,  setResult]  = useState(null);

  const setField = (k, v) => setInputs(p => ({ ...p, [k]: v }));

  async function execute() {
    setLoading(true); setResult(null);
    try {
      const pathParams = Object.fromEntries(ep.pathParams.map(k => [k, inputs[k] ?? '']));
      const targetUrl  = buildUrl(backend, ep.path, pathParams);
      const body = ep.bodyFields
        ? JSON.stringify(Object.fromEntries(ep.bodyFields.map(f => [f, f === 'price' ? Number(inputs[f]) : (inputs[f] ?? '')])))
        : undefined;
      const res  = await fetch(`/api/proxy?url=${encodeURIComponent(targetUrl)}`, {
        method: ep.method, headers: { 'Content-Type': 'application/json' }, body,
      });
      setResult({ status: res.status, data: await res.json() });
    } catch (err) {
      setResult({ status: 0, data: { error: err.message } });
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="endpoint-card">
      <div className="endpoint-header" onClick={() => setOpen(o => !o)}>
        <span className="method-badge" style={{ background: METHOD_COLOR[ep.method] }}>{ep.method}</span>
        <span className="endpoint-path">{ep.path}</span>
        <span className="endpoint-summary">{ep.summary}</span>
        <span className={`endpoint-chevron${open ? ' open' : ''}`}>▼</span>
      </div>

      {open && (
        <div className="endpoint-body">
          {ep.pathParams.length > 0 && (
            <div className="form-grid" style={{ gridTemplateColumns: '1fr' }}>
              {ep.pathParams.map(p => (
                <div className="form-group" key={p}>
                  <label>{p} <span style={{ color: '#f93e3e' }}>*</span></label>
                  <input type="number" placeholder={`${p} (숫자)`} value={inputs[p] ?? ''}
                    onChange={e => setField(p, e.target.value)} />
                </div>
              ))}
            </div>
          )}

          {ep.bodyFields && (
            <div className="form-grid">
              {ep.bodyFields.map(f => (
                <div className="form-group" key={f}>
                  <label>{f}{(f === 'name' || f === 'price') && <span style={{ color: '#f93e3e' }}> *</span>}</label>
                  <input
                    type={f === 'price' ? 'number' : 'text'}
                    placeholder={f === 'name' ? '상품명' : f === 'price' ? '가격 (숫자)' : '설명 (선택)'}
                    value={inputs[f] ?? ''} onChange={e => setField(f, e.target.value)}
                  />
                </div>
              ))}
            </div>
          )}

          <button className="btn-execute" onClick={execute} disabled={loading}>
            {loading ? '실행 중...' : '▶  Execute'}
          </button>

          {(loading || result) && (
            <div className="response-box">
              <div className="response-header">
                <span className="resp-label">Response</span>
                {result && (
                  <span className={`status-badge ${result.status && result.status < 400 ? 'status-ok' : 'status-err'}`}>
                    {result.status || 'ERR'}
                  </span>
                )}
              </div>
              {loading
                ? <p className="loading">요청 중...</p>
                : <pre className="response-body">{JSON.stringify(result.data, null, 2)}</pre>
              }
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ── Home ──────────────────────────────────────────────────────────────────────

export default function Home() {
  const [selected,      setSelected]      = useState(null);
  const [sidebarWidth,  setSidebarWidth]  = useState(SIDEBAR_DEFAULT);
  const [sidebarOpen,   setSidebarOpen]   = useState(true);
  const [mobileMenuOpen,setMobileMenuOpen]= useState(false);
  const isDragging  = useRef(false);
  const startX      = useRef(0);
  const startWidth  = useRef(SIDEBAR_DEFAULT);

  // ── 드래그 핸들러 ────────────────────────────────────────────────────────────
  const onMouseDown = useCallback((e) => {
    isDragging.current = true;
    startX.current     = e.clientX;
    startWidth.current = sidebarWidth;
    document.body.style.cursor    = 'col-resize';
    document.body.style.userSelect = 'none';
  }, [sidebarWidth]);

  useEffect(() => {
    const onMouseMove = (e) => {
      if (!isDragging.current) return;
      const delta = e.clientX - startX.current;
      const next  = Math.min(SIDEBAR_MAX, Math.max(SIDEBAR_MIN, startWidth.current + delta));
      setSidebarWidth(next);
    };
    const onMouseUp = () => {
      if (!isDragging.current) return;
      isDragging.current = false;
      document.body.style.cursor     = '';
      document.body.style.userSelect = '';
    };
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup',   onMouseUp);
    return () => {
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup',   onMouseUp);
    };
  }, []);

  const selectBackend = (b) => {
    setSelected(b);
    setMobileMenuOpen(false);
  };

  return (
    <>
      <Head>
        <title>Simple REST CRUD Demo</title>
        <meta name="viewport" content="width=device-width, initial-scale=1" />
      </Head>

      {/* ── Header ── */}
      <header className="header">
        {/* 모바일 햄버거 */}
        <button className="hamburger" onClick={() => setMobileMenuOpen(o => !o)} aria-label="메뉴">
          {mobileMenuOpen ? '✕' : '☰'}
        </button>
        <h1>Simple REST CRUD Demo</h1>
        <span className="badge">Swagger-like</span>
        {selected && (
          <span className="header-backend">
            <span className="tech-badge-sm" style={{ background: selected.color }}>{selected.tag}</span>
            :{selected.port}
          </span>
        )}
      </header>

      {/* ── 모바일 오버레이 메뉴 ── */}
      {mobileMenuOpen && (
        <div className="mobile-overlay" onClick={() => setMobileMenuOpen(false)}>
          <div className="mobile-sidebar" onClick={e => e.stopPropagation()}>
            <p className="sidebar-title">백엔드 선택</p>
            {BACKENDS.map(b => (
              <div key={b.port}
                className={`backend-item${selected?.port === b.port ? ' active' : ''}`}
                onClick={() => selectBackend(b)}>
                <span className="tech-badge" style={{ background: b.color }}>{b.tag}</span>
                <span className="name">{b.label}</span>
                <span className="port">:{b.port}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="layout">
        {/* ── 데스크톱 사이드바 ── */}
        <div
          className={`sidebar-wrap${sidebarOpen ? '' : ' collapsed'}`}
          style={{ width: sidebarOpen ? sidebarWidth : 0 }}
        >
          <aside className="sidebar">
            <p className="sidebar-title">백엔드 선택</p>
            {BACKENDS.map(b => (
              <div key={b.port}
                className={`backend-item${selected?.port === b.port ? ' active' : ''}`}
                onClick={() => setSelected(b)}>
                <span className="tech-badge" style={{ background: b.color }}>{b.tag}</span>
                <span className="name">{b.label}</span>
                <span className="port">:{b.port}</span>
              </div>
            ))}
          </aside>

          {/* 드래그 핸들 */}
          {sidebarOpen && (
            <div className="resize-handle" onMouseDown={onMouseDown}>
              <div className="resize-grip" />
            </div>
          )}
        </div>

        {/* 사이드바 토글 버튼 */}
        <button
          className="sidebar-toggle"
          onClick={() => setSidebarOpen(o => !o)}
          title={sidebarOpen ? '사이드바 숨기기' : '사이드바 보이기'}
        >
          {sidebarOpen ? '◀' : '▶'}
        </button>

        {/* ── 메인 콘텐츠 ── */}
        <main className="main">
          {selected ? (
            <>
              <div className="api-title">
                <h2>/api/items</h2>
                <span className="base-url">http://localhost:{selected.port}</span>
              </div>
              {ENDPOINTS.map(ep => (
                <EndpointCard key={ep.key} ep={ep} backend={selected} />
              ))}
            </>
          ) : (
            <div className="no-backend">
              <span className="icon">⬅</span>
              <p>좌측에서 백엔드를 선택하세요</p>
              <p className="sub">모바일에서는 상단 ☰ 버튼을 누르세요</p>
            </div>
          )}
        </main>
      </div>
    </>
  );
}
