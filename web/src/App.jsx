import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  BookOpen, GraduationCap, LogOut, RefreshCw, UserRound, Users,
  AlertTriangle, CheckCircle2, LoaderCircle, PlusCircle, Trash2,
  Wifi, WifiOff, CreditCard
} from 'lucide-react';

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID || '';
const AUTH_KEY = 'enrollment.auth.v1';
// Share one refresh request within this tab so simultaneous 401 responses do not race a rotating token.
let refreshInFlight = null;
const SERVICE_PATHS = {
  auth: '/status/auth', courses: '/status/courses', enrollments: '/status/enrollments', grades: '/status/grades'
};
const SERVICE_LABELS = {
  auth: 'Authentication service', courses: 'Course service', enrollments: 'Enrollment service', grades: 'Grade service'
};

// sessionStorage is intentionally per-tab. localStorage is shared by tabs and let the last login
// silently replace the other tab's account/token.
function readAuth() { try { return JSON.parse(sessionStorage.getItem(AUTH_KEY) || 'null'); } catch { return null; } }
function storeAuth(value) { sessionStorage.setItem(AUTH_KEY, JSON.stringify(value)); }
function clearAuth() { sessionStorage.removeItem(AUTH_KEY); }

async function readBody(response) {
  const raw = await response.text();
  if (!raw) return {};
  try { return JSON.parse(raw); } catch { return { message: raw }; }
}
function getMessage(body, fallback) {
  return body?.message || body?.detail || body?.error || (typeof body === 'string' ? body : '') || fallback;
}
function serviceForPath(path) {
  if (path.startsWith('/api/auth')) return 'auth';
  if (path.startsWith('/api/courses')) return 'courses';
  if (path.startsWith('/api/enrollments')) return 'enrollments';
  if (path.startsWith('/api/grades')) return 'grades';
  return null;
}
function timeRange(section) { return `${section.startTime || 'TBA'} - ${section.endTime || 'TBA'}`; }

function App() {
  const [auth, setAuth] = useState(readAuth);
  const [mode, setMode] = useState('login');
  const [form, setForm] = useState({ usernameOrEmail: 'student1', password: 'Student123!', username: '', email: '' });
  const [courses, setCourses] = useState([]);
  const [enrollments, setEnrollments] = useState([]);
  const [grades, setGrades] = useState([]);
  const [sections, setSections] = useState([]);
  const [notice, setNotice] = useState(null);
  const [busy, setBusy] = useState('');
  const [loadingDashboard, setLoadingDashboard] = useState(false);
  const [gradeInputs, setGradeInputs] = useState({});
  const [serviceStatus, setServiceStatus] = useState({ auth: false, courses: false, enrollments: false, grades: false });

  const updateAuth = useCallback((data) => {
    const next = { accessToken: data.accessToken, refreshToken: data.refreshToken, user: data.user, expiresAt: Date.now() + (data.expiresInSeconds || 600) * 1000 };
    storeAuth(next);
    setAuth(next);
  }, []);

  const refreshAccess = useCallback(async (current) => {
    if (!current?.refreshToken) throw new Error('Your session expired. Please sign in again.');
    if (refreshInFlight?.refreshToken === current.refreshToken) return refreshInFlight.promise;

    const refreshToken = current.refreshToken;
    const promise = (async () => {
      let response;
      try {
        response = await fetch('/api/auth/refresh', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken })
        });
      } catch {
        throw new Error('Authentication service is currently unavailable. Please try again later.');
      }
      const body = await readBody(response);
      if (!response.ok) throw new Error(getMessage(body, 'Could not refresh the session. Please sign in again.'));
      const next = { accessToken: body.accessToken, refreshToken: body.refreshToken, user: body.user, expiresAt: Date.now() + (body.expiresInSeconds || 600) * 1000 };
      storeAuth(next);
      setAuth(next);
      return next;
    })();
    refreshInFlight = { refreshToken, promise };
    try {
      return await promise;
    } finally {
      if (refreshInFlight?.promise === promise) refreshInFlight = null;
    }
  }, []);

  const api = useCallback(async (path, options = {}, retried = false) => {
    const current = readAuth() || auth;
    const headers = { ...(options.headers || {}) };
    if (options.body !== undefined && !(options.body instanceof FormData)) headers['Content-Type'] = 'application/json';
    if (current?.accessToken) headers.Authorization = `Bearer ${current.accessToken}`;

    let response;
    try {
      response = await fetch(path, { ...options, headers });
    } catch {
      const key = serviceForPath(path);
      throw new Error(`${key ? SERVICE_LABELS[key] : 'The service'} is currently unavailable. Please try again later.`);
    }

    if (response.status === 401 && current?.refreshToken && !retried && !path.startsWith('/api/auth/')) {
      try {
        const next = await refreshAccess(current);
        return api(path, { ...options, headers: { ...(options.headers || {}), Authorization: `Bearer ${next.accessToken}` } }, true);
      } catch (error) {
        if (/expired|revoked|sign in again/i.test(error.message)) {
          clearAuth();
          setAuth(null);
        }
        throw error;
      }
    }

    const body = await readBody(response);
    if (!response.ok) {
      const key = serviceForPath(path);
      if ([502, 504].includes(response.status)) {
        throw new Error(`${SERVICE_LABELS[key] || 'Service'} is currently unavailable. Please try again later.`);
      }
      const message = getMessage(body, response.status === 503
        ? `${SERVICE_LABELS[key] || 'Service'} is currently unavailable. Please try again later.`
        : `Request failed (${response.status}).`);
      throw new Error(message || `Request failed (${response.status}). The server did not provide an error message.`);
    }
    return body;
  }, [auth, refreshAccess]);

  const showError = (error) => setNotice({ type: 'error', text: error?.message || 'The operation failed.' });
  const showSuccess = (message) => setNotice({ type: 'success', text: message });

  const refreshStatuses = useCallback(async () => {
    const checks = await Promise.all(Object.entries(SERVICE_PATHS).map(async ([key, path]) => {
      try {
        const response = await fetch(path, { cache: 'no-store' });
        return [key, response.ok];
      } catch {
        return [key, false];
      }
    }));
    setServiceStatus(Object.fromEntries(checks));
  }, []);

  useEffect(() => {
    refreshStatuses();
    const timer = window.setInterval(refreshStatuses, 6000);
    return () => window.clearInterval(timer);
  }, [refreshStatuses]);

  // Toast notifications disappear automatically instead of occupying the page layout.
  useEffect(() => {
    if (!notice) return undefined;
    const timer = window.setTimeout(() => setNotice(null), 4800);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const loadDashboard = useCallback(async (showLoadErrors = true) => {
    const current = readAuth() || auth;
    if (!current?.user) return;
    setLoadingDashboard(true);
    try {
      const requestSpecs = current.user.role === 'STUDENT'
        ? [
            { service: 'courses', promise: api('/api/courses') },
            { service: 'enrollments', promise: api('/api/enrollments/my') },
            { service: 'grades', promise: api('/api/grades/my') }
          ]
        : [
            { service: 'courses', promise: api('/api/courses') },
            { service: 'courses', promise: api('/api/courses/my-sections') }
          ];
      const results = await Promise.allSettled(requestSpecs.map((item) => item.promise));
      if (results[0].status === 'fulfilled') setCourses(results[0].value);
      if (current.user.role === 'STUDENT') {
        if (results[1].status === 'fulfilled') setEnrollments(results[1].value);
        if (results[2].status === 'fulfilled') setGrades(results[2].value);
      } else if (results[1].status === 'fulfilled') {
        setSections(results[1].value);
      }
      const failedServices = [...new Set(results
        .map((result, index) => result.status === 'rejected' ? requestSpecs[index].service : null)
        .filter(Boolean))];
      if (showLoadErrors && failedServices.length) {
        const names = failedServices.map((service) => SERVICE_LABELS[service]);
        const joined = names.length === 1 ? names[0]
          : names.length === 2 ? `${names[0]} and ${names[1]}`
          : `${names.slice(0, -1).join(', ')}, and ${names[names.length - 1]}`;
        setNotice({ type: 'error', text: `${joined} ${names.length === 1 ? 'is' : 'are'} currently unavailable. Please try again later.` });
      }
      await refreshStatuses();
    } finally {
      setLoadingDashboard(false);
    }
  }, [api, auth, refreshStatuses]);

  useEffect(() => {
    if (auth?.user) loadDashboard(false);
    // Account changes are handled by login; API callbacks must not trigger duplicate dashboard loads.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auth?.user?.id]);

  useEffect(() => {
    if (!GOOGLE_CLIENT_ID || auth?.user) return undefined;
    const script = document.createElement('script');
    script.src = 'https://accounts.google.com/gsi/client';
    script.async = true;
    script.defer = true;
    script.onload = () => {
      if (!window.google?.accounts?.id) return;
      window.google.accounts.id.initialize({ client_id: GOOGLE_CLIENT_ID, callback: async (response) => {
        setBusy('google');
        try {
          const data = await api('/api/auth/google', { method: 'POST', body: JSON.stringify({ credential: response.credential }) });
          updateAuth(data);
        } catch (error) { showError(error); } finally { setBusy(''); }
      } });
      const target = document.getElementById('google-signin-button');
      if (target) window.google.accounts.id.renderButton(target, { theme: 'outline', size: 'large', shape: 'rectangular', text: 'continue_with', width: 320 });
    };
    document.body.appendChild(script);
    return () => { if (script.parentNode) script.parentNode.removeChild(script); };
  }, [api, updateAuth, auth?.user?.id]);

  const handleLogin = async (event) => {
    event.preventDefault();
    setBusy('auth');
    try {
      const path = mode === 'register' ? '/api/auth/register' : '/api/auth/login';
      const body = mode === 'register'
        ? JSON.stringify({ username: form.username, email: form.email, password: form.password })
        : JSON.stringify({ usernameOrEmail: form.usernameOrEmail, password: form.password });
      const data = await api(path, { method: 'POST', body });
      if (mode === 'register') {
        setMode('login');
        setForm((value) => ({ ...value, usernameOrEmail: form.username, password: '' }));
        showSuccess('Account created. Sign in with your new credentials.');
      } else updateAuth(data);
    } catch (error) { showError(error); } finally { setBusy(''); }
  };

  const logout = async () => {
    setBusy('logout');
    let message = 'Signed out.';
    try { await api('/api/auth/logout', { method: 'POST', body: JSON.stringify({ refreshToken: auth?.refreshToken }) }); }
    catch (error) { message = `Signed out on this tab. Server-side session revocation could not be confirmed: ${error.message}`; }
    clearAuth();
    setAuth(null);
    setCourses([]); setEnrollments([]); setGrades([]); setSections([]);
    setNotice({ type: 'success', text: message });
    setBusy('');
  };

  const enroll = async (courseId, sectionId) => {
    if (enrollments.length >= 5) { showError(new Error('You have reached the 15-credit limit (5 courses × 3 credits). Unenroll from a course before adding another.')); return; }
    setBusy(`enroll-${sectionId}`);
    try {
      const result = await api('/api/enrollments', { method: 'POST', body: JSON.stringify({ courseId, sectionId }) });
      await loadDashboard(false);
      showSuccess(result.message || 'Enrollment successful.');
    } catch (error) { showError(error); } finally { setBusy(''); }
  };

  const unenroll = async (enrollment) => {
    const confirmed = window.confirm(`Unenroll from ${enrollment.courseCode} ${enrollment.sectionName}? Any final grade recorded for this section will also be deleted.`);
    if (!confirmed) return;
    setBusy(`unenroll-${enrollment.id}`);
    try {
      const result = await api(`/api/enrollments/${enrollment.id}`, { method: 'DELETE' });
      await loadDashboard(false);
      showSuccess(result.message || 'You have been unenrolled.');
    } catch (error) { showError(error); } finally { setBusy(''); }
  };

  const uploadGrade = async (sectionId) => {
    const entry = gradeInputs[sectionId] || {};
    if (!entry.studentId || entry.finalGrade === undefined || entry.finalGrade === '') {
      showError(new Error('Enter a student ID and final grade.'));
      return;
    }
    setBusy(`grade-${sectionId}`);
    try {
      const result = await api(`/api/grades/sections/${sectionId}/students/${entry.studentId}`, {
        method: 'PUT', body: JSON.stringify({ finalGrade: Number(entry.finalGrade) })
      });
      showSuccess(result.message || 'Final grade saved.');
    } catch (error) { showError(error); } finally { setBusy(''); }
  };

  const title = auth?.user?.role === 'FACULTY' ? 'Faculty workspace' : 'Student workspace';
  const displayName = auth?.user?.fullName || auth?.user?.username || 'User';
  const roleLabel = auth?.user?.role === 'FACULTY' ? 'Associate Professor' : 'Undergraduate';
  const enrolledCourseIds = useMemo(() => new Set(enrollments.map((item) => Number(item.courseId))), [enrollments]);
  const creditsUsed = enrollments.length * 3;
  const creditsRemaining = Math.max(0, 15 - creditsUsed);

  if (!auth?.user) return (
    <main className="auth-shell">
      <div className="auth-left">
        <div className="brand brand-plain">EZ Enroll</div>
        <div className="hero-copy">
          <div className="eyebrow">DISTRIBUTED ENROLLMENT SYSTEM</div>
          <h1>Possibly Better<br/><em>than ArchersHub</em></h1>
          <p>A simple academic portal for course enrollment, academic records, and faculty grade management.</p>
        </div>
        <div className="auth-footer">Spring Boot · gRPC · React · PostgreSQL</div>
      </div>
      <div className="auth-right">
        <div className="auth-card">
          <div className="mobile-brand brand brand-plain">EZ Enroll</div>
          <div className="auth-heading">
            <div className="eyebrow">WELCOME</div>
            <h2>{mode === 'register' ? 'Create your account' : 'Sign in to continue'}</h2>
            <p>{mode === 'register' ? 'Student accounts can be created here.' : 'Access your courses and academic records.'}</p>
          </div>
          <form onSubmit={handleLogin} className="auth-form">
            {mode === 'register' ? <>
              <label>Username<input required value={form.username} onChange={(event)=>setForm({...form,username:event.target.value})} placeholder="your username"/></label>
              <label>Email address<input required type="email" value={form.email} onChange={(event)=>setForm({...form,email:event.target.value})} placeholder="you@example.com"/></label>
            </> : <label>Username or email<input required value={form.usernameOrEmail} onChange={(event)=>setForm({...form,usernameOrEmail:event.target.value})} placeholder="student1"/></label>}
            <label>Password<input required type="password" minLength={8} value={form.password} onChange={(event)=>setForm({...form,password:event.target.value})} placeholder="At least 8 characters"/></label>
            <button className="primary full" disabled={busy==='auth'}>{busy==='auth' ? <><LoaderCircle className="spin" size={16}/> Please wait</> : mode==='register' ? 'Create student account' : 'Sign in'}</button>
          </form>
          {mode==='login' && <>
            <div className="separator"><span>OR</span></div>
            {GOOGLE_CLIENT_ID ? <div id="google-signin-button" className="google-button"/> : <button className="google-placeholder" disabled title="Set GOOGLE_CLIENT_ID to enable Google sign-in"><span className="google-g">G</span> Google sign-in (not configured)</button>}
          </>}
          <p className="switch-mode">{mode==='register' ? 'Already have an account?' : 'New student?'} <button onClick={()=>{setMode(mode==='register'?'login':'register');setNotice(null);}}>{mode==='register'?'Sign in':'Create an account'}</button></p>
          <div className="demo-box"><strong>Demo accounts</strong><div><span>Student</span><code>student1 / Student123!</code></div><div><span>Faculty</span><code>faculty1 / Faculty123!</code></div><div><span>Other faculty</span><code>faculty2 or faculty3 / Faculty123!</code></div></div>
        </div>
      </div>
      {notice && <Toast notice={notice}/>}
    </main>
  );

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand brand-plain">EZ Enroll</div>
        <div className="side-label">WORKSPACE</div>
        <div className="side-user">
          <div className="avatar">{displayName.slice(0,1).toUpperCase()}</div>
          <div><strong>{displayName}</strong><small>{auth.user.role}</small></div>
        </div>
        <nav><button className="nav-item active"><BookOpen size={17}/> Overview</button><button className="nav-item" onClick={()=>loadDashboard()}><RefreshCw size={17}/> Refresh data</button></nav>
        <div className="sidebar-bottom">
          <button className="signout-button" onClick={logout} disabled={busy==='logout'}><LogOut size={17}/> Sign out</button>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div><div className="eyebrow">ACADEMIC PORTAL / OVERVIEW</div><h1>{title}</h1></div>
          <div className="top-actions"><span className="role-chip"><UserRound size={14}/>{roleLabel}</span><ServiceStatus online={serviceStatus.auth} label="Auth service"/><button className="icon-button" title="Refresh" onClick={()=>loadDashboard()} disabled={loadingDashboard}><RefreshCw size={17} className={loadingDashboard?'spin':''}/></button></div>
        </header>
        <section className="welcome-row"><div><h2>Hello, {displayName}.</h2><p>{auth.user.role==='STUDENT'?'Explore open sections, manage your enrollment, and review your final grades.':'Review your assigned sections and submit or update students’ final grades.'}</p></div><div className="welcome-icon">{auth.user.role==='STUDENT'?<BookOpen size={25}/>:<GraduationCap size={25}/>}</div></section>

        {auth.user.role==='STUDENT' ? <>
          <section className="stats-grid">
            <Stat icon={<BookOpen size={18}/>} label="Available courses" value={courses.length}/>
            <Stat icon={<Users size={18}/>} label="Current enrollments" value={enrollments.length}/>
            <Stat icon={<GraduationCap size={18}/>} label="Grades recorded" value={grades.length}/>
          </section>

          <section className="credit-panel">
            <div className="credit-title"><div className="credit-icon"><CreditCard size={18}/></div><div><strong>Enrollment credit tracker</strong><small>15-credit maximum · 3 credits per course</small></div></div>
            <div className="credit-total"><strong>{creditsUsed} / 15</strong><span>{creditsRemaining} credits remaining</span></div>
            <div className="credit-bar"><span style={{width:`${Math.min(100,(creditsUsed/15)*100)}%`}}/></div>
            <div className="credit-foot"><span>{enrollments.length} of 5 courses enrolled</span><span>{5-enrollments.length} course slot{5-enrollments.length===1?'':'s'} remaining</span></div>
          </section>

          <section className="content-section">
            <div className="section-heading"><div><div className="eyebrow">COURSE CATALOG</div><h2>Available courses</h2></div><div className="heading-meta"><span className="muted-tag">3 credits each</span><ServiceStatus online={serviceStatus.courses}/></div></div>
            {serviceStatus.courses && courses.length > 0 ? <div className="course-grid">{courses.map(course=><article className="course-card" key={course.id}>
              <div className="course-code">{course.code}</div><h3>{course.title}</h3><p>{course.description}</p>
              <div className="section-list">{course.sections?.map(section=><div className="section-row" key={section.id}>
                <div className="section-info"><strong>{section.sectionName}</strong><small>{section.facultyName}</small><small>[{section.meetingDays}] {timeRange(section)}</small><small>Capacity: {section.capacity} · Slots left: {section.slotsLeft}</small></div>
                <button className="small-button" disabled={!section.enrollmentOpen || section.slotsLeft<=0 || enrolledCourseIds.has(Number(course.id)) || enrollments.length>=5 || busy===`enroll-${section.id}`} onClick={()=>enroll(course.id,section.id)}>
                  {busy===`enroll-${section.id}`?<LoaderCircle size={14} className="spin"/>:enrolledCourseIds.has(Number(course.id))?'Enrolled':enrollments.length>=5?'Limit reached':!section.enrollmentOpen?'Closed':section.slotsLeft<=0?'Full':'Enroll'}
                </button>
              </div>)}</div>
              {enrolledCourseIds.has(Number(course.id)) && <div className="inline-note"><CheckCircle2 size={14}/> You are already enrolled in this course.</div>}
            </article>)}</div> : <EmptyState online={serviceStatus.courses} title="Available courses are unavailable" message="The course service is offline. Please try again later." noDataTitle="No courses available" noDataMessage="There are currently no courses in the catalog." />}
          </section>

          <section className="content-section">
            <div className="section-heading"><div><div className="eyebrow">YOUR RECORD</div><h2>My enrollments</h2></div><ServiceStatus online={serviceStatus.enrollments}/></div>
            {serviceStatus.enrollments ? <div className="table-wrap"><table><thead><tr><th>Course</th><th>Section</th><th>Faculty</th><th>Class time</th><th>Enrolled at</th><th>Action</th></tr></thead><tbody>
              {enrollments.map(item=><tr key={item.id}><td><strong>{item.courseCode}</strong></td><td>{item.sectionName}</td><td>{item.facultyName}</td><td>[{item.meetingDays}] {timeRange(item)}</td><td>{new Date(item.enrolledAt).toLocaleString()}</td><td><button className="danger-button" disabled={busy===`unenroll-${item.id}`} onClick={()=>unenroll(item)}>{busy===`unenroll-${item.id}`?<LoaderCircle size={14} className="spin"/>:<><Trash2 size={13}/> Unenroll</>}</button></td></tr>)}
              {!enrollments.length&&<tr><td colSpan="6" className="empty-cell">You are not enrolled in any courses.</td></tr>}
            </tbody></table></div> : <EmptyState online={false} title="Enrollment service offline" message="Your enrollment records cannot be loaded right now. Please try again later."/>}
          </section>

          <section className="content-section">
            <div className="section-heading"><div><div className="eyebrow">ACADEMIC HISTORY</div><h2>Previous grades</h2></div><ServiceStatus online={serviceStatus.grades}/></div>
            {serviceStatus.grades ? <div className="table-wrap"><table><thead><tr><th>Course</th><th>Section</th><th>Final grade</th><th>Last updated</th></tr></thead><tbody>
              {grades.map(grade=><tr key={grade.id}><td><strong>{grade.courseCode}</strong></td><td>{grade.sectionId}</td><td><strong className="grade-value">{Number(grade.finalGrade).toFixed(2)}</strong></td><td>{new Date(grade.updatedAt).toLocaleString()}</td></tr>)}
              {!grades.length&&<tr><td colSpan="4" className="empty-cell">No grades have been recorded yet.</td></tr>}
            </tbody></table></div> : <EmptyState online={false} title="Grade service offline" message="Your previous grades cannot be loaded right now. Please try again later."/>}
          </section>
        </> : <>
          <section className="stats-grid"><Stat icon={<BookOpen size={18}/>} label="Assigned sections" value={sections.length}/><Stat icon={<Users size={18}/>} label="Courses represented" value={new Set(sections.map(section=>section.courseId)).size}/><Stat icon={<GraduationCap size={18}/>} label="Access level" value="Faculty"/></section>
          <section className="content-section">
            <div className="section-heading"><div><div className="eyebrow">TEACHING ASSIGNMENTS</div><h2>My assigned sections</h2></div><div className="heading-meta"><span className="muted-tag">One faculty per section</span><ServiceStatus online={serviceStatus.courses} label="Courses"/><ServiceStatus online={serviceStatus.enrollments} label="Enrollment"/><ServiceStatus online={serviceStatus.grades} label="Grades"/></div></div>
            {!serviceStatus.courses ? <EmptyState online={false} title="Course service offline" message="Assigned sections cannot be loaded right now. Please try again later."/> : <div className="faculty-grid">{sections.map(section=><article className="faculty-card" key={section.sectionId}>
              <div className="faculty-card-head"><div className="course-code">{section.courseCode}</div><span className="section-id">{section.sectionName} · Section #{section.sectionId}</span></div>
              <h3>{section.courseTitle}</h3><p>{section.facultyName} · [{section.meetingDays}] {timeRange(section)}</p>
              <div className="section-capacity">Capacity: {section.capacity} · Slots left: {section.slotsLeft}</div>
              <div className="grade-form">
                <label>Student ID<input type="number" min="1" placeholder="e.g. 1" value={gradeInputs[section.sectionId]?.studentId||''} onChange={event=>setGradeInputs(value=>({...value,[section.sectionId]:{...value[section.sectionId],studentId:event.target.value}}))}/></label>
                <label>Final grade (0–100)<input type="number" min="0" max="100" step="0.01" placeholder="e.g. 92.50" value={gradeInputs[section.sectionId]?.finalGrade??''} onChange={event=>setGradeInputs(value=>({...value,[section.sectionId]:{...value[section.sectionId],finalGrade:event.target.value}}))}/></label>
                <button className="primary" onClick={()=>uploadGrade(section.sectionId)} disabled={!serviceStatus.grades || !serviceStatus.enrollments || !serviceStatus.courses || busy===`grade-${section.sectionId}`}>{busy===`grade-${section.sectionId}`?<LoaderCircle size={15} className="spin"/>:<PlusCircle size={15}/>} Save final grade</button>
              </div>
            </article>)}{!sections.length&&<div className="empty-panel">No sections are assigned to this faculty account.</div>}</div>}
          </section>
        </>}
        <footer className="page-footer">EZ Enroll <span>·</span> Spring Boot microservices <span>·</span> gRPC <span>·</span> PostgreSQL</footer>
      </main>
      {notice && <Toast notice={notice}/>}
    </div>
  );
}

function Stat({icon,label,value}) { return <div className="stat-card"><div className="stat-icon">{icon}</div><div><span>{label}</span><strong>{value}</strong></div></div>; }
function Toast({notice}) { return <div className={`toast ${notice.type}`} role="status"><span>{notice.type==='error'?<AlertTriangle size={18}/>:<CheckCircle2 size={18}/>}</span><p>{notice.text}</p></div>; }
function ServiceStatus({online,label}) { const statusText = label ? `${label} · ${online?'Online':'Offline'}` : (online?'Online':'Offline'); return <span className={`service-status ${online?'online':'offline'}`} title={`${label || 'Service'} ${online?'online':'offline'}`}>{online?<Wifi size={12}/>:<WifiOff size={12}/>} {statusText}</span>; }
function EmptyState({online,title,message,noDataTitle,noDataMessage}) {
  const heading = online ? (noDataTitle || 'Nothing to show yet') : title;
  const detail = online ? (noDataMessage || 'There are no records to display.') : message;
  return <div className={`empty-state ${online?'empty':'offline-state'}`}><div className="empty-icon">{online?<BookOpen size={22}/>:<WifiOff size={22}/>}</div><strong>{heading}</strong><p>{detail}</p></div>;
}

export default App;
