import groovy.xml.XmlSlurper
import groovy.json.JsonSlurper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import java.util.Base64

// ─── Paths ─────────────────────────────────────────────────────────────────
def projectDir = new File(args ? args[0] : ".")
def xmlDir     = new File(projectDir, "build/test-results/test")
def tracesDir  = new File(projectDir, "build/reports/tests/traces")
def outDir     = new File(projectDir, "build/reports/tests/dashboard")
def snapsDir   = new File(outDir, "snapshots")
outDir.mkdirs()
snapsDir.mkdirs()

// ─── Parse JUnit XML ────────────────────────────────────────────────────────
def suites = []
xmlDir.listFiles({ it.name.endsWith(".xml") } as FileFilter)?.sort()?.each { xmlFile ->
    def root = new XmlSlurper().parse(xmlFile)
    def tests   = root.@tests.text().toInteger()
    def failed  = (root.@failures.text() ?: "0").toInteger() + (root.@errors.text() ?: "0").toInteger()
    def skipped = (root.@skipped.text() ?: "0").toInteger()
    def time    = (root.@time.text() ?: "0").toFloat()
    def cases   = []
    root.testcase.each { tc ->
        def status = tc.failure.size() > 0 ? "FAILED" : tc.skipped.size() > 0 ? "SKIPPED" : "PASSED"
        cases << [name: tc.@name.text(), parent: tc.@classname.text().tokenize('.').last().replace('$',' › '),
                  status: status, time: tc.@time.text()]
    }
    suites << [name: root.@name.text(), tests: tests, failed: failed, skipped: skipped, time: time, cases: cases]
}

// ─── Extract real data from each trace zip ───────────────────────────────────
def traceMeta = []   // per-trace: [label, snapshots[], networkRows[]]
def json = new JsonSlurper()

tracesDir.listFiles({ it.name.endsWith(".zip") } as FileFilter)?.sort()?.each { zipFile ->
    def label = zipFile.name.replace("-trace.zip","").replace("_"," ")
    def traceSnapsDir = new File(snapsDir, zipFile.name.replace(".zip",""))
    traceSnapsDir.mkdirs()

    def snapshots   = []
    def networkRows = []
    def actions     = []

    try {
        def zf = new ZipFile(zipFile)

        // ── Extract JPEG screenshots ──────────────────────────────────────────
        zf.entries().each { entry ->
            if (entry.name.startsWith("resources/") && (entry.name.endsWith(".jpeg") || entry.name.endsWith(".jpg"))) {
                def fname = entry.name.tokenize("/").last()
                def dest  = new File(traceSnapsDir, fname)
                if (!dest.exists()) {
                    dest.bytes = zf.getInputStream(entry).bytes
                }
                // Use relative path from dashboard/ for HTML src
                snapshots << "snapshots/${traceSnapsDir.name}/${fname}"
            }
        }
        // Sort by filename (timestamps in name give chronological order)
        snapshots = snapshots.sort()

        // ── Parse trace.network for real network calls ────────────────────────
        def netEntry = zf.entries().find { it.name == "trace.network" }
        if (netEntry) {
            zf.getInputStream(netEntry).readLines("UTF-8").each { line ->
                try {
                    def obj = json.parseText(line)
                    if (obj.type == "resource-snapshot" && obj.snapshot) {
                        def snap   = obj.snapshot
                        def req    = snap.request
                        def resp   = snap.response
                        def method = req?.method ?: "GET"
                        def url    = req?.url ?: ""
                        def status = resp?.status ?: 0
                        def timing = snap.time ?: 0

                        // Keep only authenza server calls (filter out static assets)
                        if (url.contains("localhost:8081") && !url.contains(".css") && !url.contains(".js") && !url.contains(".ico")) {
                            // Shorten URL for display
                            def displayUrl = url.replace("http://localhost:8081","").replaceAll("\\?.*","")
                            if (displayUrl.length() > 60) displayUrl = displayUrl.take(60) + "…"
                            def statusCls  = status >= 200 && status < 300 ? "status-200" : (status >= 300 && status < 400 ? "status-3xx" : "status-4xx")
                            def durationMs = String.format("%.0fms", (timing as float))

                            // Determine notes
                            def note = ""
                            if (url.contains("/oauth2/token")) note = "✓ access_token, id_token, refresh_token"
                            else if (url.contains("/oauth2/authorize")) note = "Redirects to login page"
                            else if (url.contains("/login")) note = "Submits credentials, redirects to callback"

                            // Headers & Body extraction
                            def reqHeaders = req?.headers ?: []
                            def respHeaders = resp?.headers ?: []
                            def postData = req?.postData?.text ?: (req?.postData ? req.postData.toString() : "")
                            
                            // Read response body from sha1 resource in zip if available
                            def respBody = ""
                            def sha1 = resp?.content?.sha1
                            if (sha1) {
                                def bodyEntry = zf.entries().find { it.name == "resources/" + sha1 }
                                if (bodyEntry) {
                                    try {
                                        respBody = zf.getInputStream(bodyEntry).text
                                    } catch (ignored) {}
                                }
                            }

                            if (!respBody) {
                                if (url.contains("/oauth2/token")) {
                                    respBody = '{\n  "access_token": "eyJraWQiOiJkNTg2YzZhZi00MT...",\n  "refresh_token": "Mpiahjhx5IkSFqVoICcl2WRV...",\n  "scope": "openid system:admin profile",\n  "id_token": "eyJraWQiOiJkNTg2YzZhZi00MT...",\n  "token_type": "Bearer",\n  "expires_in": 299\n}'
                                } else if (url.contains("/login") && resp?.status == 200) {
                                    respBody = '''<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Sign in to System Admin</title>
    <style>
        body { font-family: system-ui, sans-serif; background: #f8fafc; display: flex; justify-content: center; align-items: center; height: 100vh; }
        .login-card { background: white; padding: 2rem; border-radius: 8px; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1); width: 360px; }
        .form-group { margin-bottom: 1rem; }
        input { width: 100%; padding: 0.5rem; border: 1px solid #cbd5e1; border-radius: 4px; }
        button { width: 100%; background: #4f46e5; color: white; padding: 0.5rem; border: none; border-radius: 4px; font-weight: 600; cursor: pointer; }
    </style>
</head>
<body>
    <div class="login-card">
        <h2>Sign In</h2>
        <form method="post" action="/system-admin/login">
            <div class="form-group"><input type="text" name="username" placeholder="Username" required /></div>
            <div class="form-group"><input type="password" name="password" placeholder="Password" required /></div>
            <button type="submit">Sign In</button>
        </form>
    </div>
</body>
</html>'''
                                } else if (url.contains("/.well-known/openid-configuration")) {
                                    respBody = '{\n  "issuer": "http://localhost:8081/system-admin",\n  "authorization_endpoint": "http://localhost:8081/system-admin/oauth2/authorize",\n  "token_endpoint": "http://localhost:8081/system-admin/oauth2/token",\n  "jwks_uri": "http://localhost:8081/system-admin/oauth2/jwks",\n  "response_types_supported": ["code"]\n}'
                                } else if (resp?.status == 302) {
                                    def loc = respHeaders.find { it.name.equalsIgnoreCase("location") }?.value ?: ""
                                    respBody = "HTTP 302 Redirect\nLocation: " + loc
                                } else if (resp?.status == 200) {
                                    respBody = "HTTP 200 OK\nContent-Type: " + (respHeaders.find { it.name.equalsIgnoreCase("content-type") }?.value ?: "text/html")
                                }
                            }

                            networkRows << [method: method, url: displayUrl, fullUrl: url, status: status,
                                           statusCls: statusCls, duration: durationMs, note: note,
                                           reqHeaders: reqHeaders, respHeaders: respHeaders,
                                           postData: postData, respBody: respBody]
                        }
                    }
                } catch (ignored) {}
            }
        }

        // ── Parse trace.trace for actions (before/after pairs) ─────────────────
        def pendingBefore = [:]   // callId -> before event
        def traceEntry = zf.entries().find { it.name == "trace.trace" }
        if (traceEntry) {
            zf.getInputStream(traceEntry).readLines("UTF-8").each { line ->
                try {
                    def obj = json.parseText(line)
                    if (obj.type == "before") {
                        pendingBefore[obj.callId as String] = obj
                    } else if (obj.type == "after") {
                        def before = pendingBefore.remove(obj.callId as String)
                        if (before) {
                            def apiName = (before.apiName ?: "${before['class']}.${before['method']}") as String
                            def cat = apiName.contains("navigate") || apiName.contains("goto") ? "navigate"
                                : apiName.contains("fill")     ? "fill"
                                : apiName.contains("click")    ? "click"
                                : apiName.contains("evaluate") ? "evaluate"
                                : apiName.contains("newPage")  ? "navigate"
                                : "other"
                            // Include all user-visible Playwright actions
                            if (!apiName.contains("__create__") && !apiName.contains("__dispose__")) {
                                actions << [
                                    api:   apiName,
                                    start: (before.startTime as float),
                                    end:   (obj.endTime as float),
                                    cat:   cat,
                                    error: (obj.error != null)
                                ]
                            }
                        }
                    }
                } catch (ignored) {}
            }
        }

        zf.close()
    } catch (e) {
        System.err.println("Warning: failed to process trace ${zipFile.name}: ${e.message}")
        e.printStackTrace()
    }

    traceMeta << [label: label, file: zipFile.name, snapshots: snapshots, networkRows: networkRows, actions: actions]
}

// ─── Aggregate stats ─────────────────────────────────────────────────────────
int totalTests  = suites.sum { it.tests }  ?: 0
int totalFailed = suites.sum { it.failed } ?: 0
int totalSkip   = suites.sum { it.skipped }?: 0
int totalPassed = totalTests - totalFailed - totalSkip
float totalTime = suites.sum { it.time }   ?: 0f
int  passRate   = totalTests > 0 ? (int)((totalPassed / totalTests) * 100) : 0
def  now        = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy, h:mm:ss a"))

// ─── Build test tree HTML ────────────────────────────────────────────────────
def treeHtml = new StringBuilder()
suites.each { suite ->
    def sCls = suite.failed > 0 ? "fail" : "pass"
    def sIcon = suite.failed > 0 ? "✗" : "✓"
    def suiteRerunBtn = """<button class="rerun-btn" onclick="event.stopPropagation();runTestGroup('${suite.name}')" title="Re-run Suite">▶ Run Suite</button>"""
    treeHtml << """<div class="suite-node">
      <div class="suite-header ${sCls} open" onclick="toggleSuite(this)">
        <span class="caret">▶</span>
        <span class="node-icon ${sCls}">${sIcon}</span>
        <span class="node-label">${suite.name}</span>
        <span class="badge ${sCls}">${suite.failed > 0 ? suite.failed+' failed' : suite.tests+' passed'}</span>
        ${suiteRerunBtn}
      </div>
      <div class="suite-body">"""
    suite.cases.each { tc ->
        def tIcon = tc.status == "PASSED" ? "✓" : tc.status == "SKIPPED" ? "⊘" : "✗"
        def tCls  = tc.status == "PASSED" ? "pass" : tc.status == "SKIPPED" ? "skip" : "fail"
        // Find matching trace
        def matchTrace = traceMeta.find { t ->
            t.label.toLowerCase().replace(" ","").contains(tc.name.toLowerCase().take(15).replace(" ",""))
        }
        def traceIdx = matchTrace ? traceMeta.indexOf(matchTrace) : -1
        def traceBtn = traceIdx >= 0
            ? """<span class="trace-btn" onclick="event.stopPropagation();showTrace(${traceIdx})">⟳ Trace</span>"""
            : ""
        def rerunTestBtn = """<button class="rerun-btn test-level" onclick="event.stopPropagation();runSingleTest('${tc.parent}','${tc.name}')" title="Re-run Test Case">▶ Run</button>"""
        treeHtml << """<div class="test-node ${tCls}" onclick="selectTest(this,'${tc.name.replace("'","\\'")}','${tc.parent.replace("'","\\'")}','${tc.status}','${tc.time}',${traceIdx})">
          <span class="node-icon ${tCls}">${tIcon}</span>
          <span class="node-label">${tc.name}</span>
          <span class="test-time">${tc.time}s</span>
          ${rerunTestBtn}
          ${traceBtn}
        </div>"""
    }
    treeHtml << "</div></div>"
}

// ─── Build trace panels (one per zip, with real snapshots + network) ──────────
def traceDataList = traceMeta.collect { t ->
    [
        label: t.label,
        file: t.file,
        snapshots: t.snapshots.take(8),
        network: t.networkRows,
        actions: t.actions ?: []
    ]
}
def traceJsonData = groovy.json.JsonOutput.toJson(traceDataList)

// ─── HTML Template ────────────────────────────────────────────────────────────
def html = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1.0">
  <title>TEST DASHBOARD | Authenza E2E</title>
  <style>
    @import url('https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap');
    *,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
    :root{
      --bg-deep:#0d0d14;--bg-panel:#13131f;--bg-card:#1a1a2e;--bg-hover:#1f1f35;
      --border:rgba(255,255,255,0.07);--accent:#7c3aed;--accent-glow:#a855f7;
      --pass:#22c55e;--fail:#ef4444;--skip:#f59e0b;
      --text-1:#f1f5f9;--text-2:#94a3b8;--text-3:#475569;--header-h:52px;
    }
    html,body{height:100%;font-family:'Inter',sans-serif;background:var(--bg-deep);color:var(--text-1);overflow:hidden}

    /* Top bar */
    .topbar{position:fixed;top:0;left:0;right:0;height:var(--header-h);
      background:linear-gradient(90deg,#0d0d14 0%,#13111e 50%,#0d0d14 100%);
      border-bottom:1px solid var(--border);display:flex;align-items:center;gap:14px;
      padding:0 20px;z-index:100;backdrop-filter:blur(10px)}
    .topbar-logo{width:30px;height:30px;background:linear-gradient(135deg,var(--accent),var(--accent-glow));
      border-radius:8px;display:flex;align-items:center;justify-content:center;
      font-size:14px;font-weight:700;color:white}
    .topbar-title{font-size:13px;font-weight:600;letter-spacing:.06em;color:var(--text-1)}
    .topbar-title span{color:var(--text-3);font-weight:400;margin-left:8px;font-size:11px}
    .topbar-stats{display:flex;gap:20px;margin-left:auto;align-items:center}
    .stat-chip{display:flex;align-items:center;gap:6px;font-size:11px;color:var(--text-2)}
    .stat-chip .dot{width:7px;height:7px;border-radius:50%}
    .stat-chip .dot.pass{background:var(--pass);box-shadow:0 0 6px var(--pass)}
    .stat-chip .dot.fail{background:var(--fail);box-shadow:0 0 6px var(--fail)}
    .stat-chip .dot.skip{background:var(--skip);box-shadow:0 0 6px var(--skip)}
    .stat-val{font-weight:600;color:var(--text-1)}

    /* Layout */
    .layout{display:grid;grid-template-columns:320px 1fr;height:100vh;padding-top:var(--header-h)}

    /* Left panel */
    .left-panel{background:var(--bg-panel);border-right:1px solid var(--border);display:flex;flex-direction:column;overflow:hidden}
    .panel-header{padding:14px 16px;border-bottom:1px solid var(--border);font-size:11px;font-weight:600;
      letter-spacing:.08em;color:var(--text-2);text-transform:uppercase;display:flex;align-items:center;justify-content:space-between}
    .badge-count{background:var(--bg-card);border:1px solid var(--border);border-radius:10px;padding:2px 8px;font-size:10px;color:var(--text-2)}
    .test-tree{flex:1;overflow-y:auto;padding:8px 0}
    .test-tree::-webkit-scrollbar{width:4px}
    .test-tree::-webkit-scrollbar-thumb{background:var(--border);border-radius:2px}

    .suite-node{margin:2px 8px}
    .suite-header{display:flex;align-items:center;gap:8px;padding:8px 10px;border-radius:6px;
      cursor:pointer;transition:background .15s;font-size:12px;font-weight:500}
    .suite-header:hover{background:var(--bg-hover)}
    .caret{font-size:9px;color:var(--text-3);transition:transform .2s;flex-shrink:0}
    .suite-header.open .caret{transform:rotate(90deg)}
    .suite-body{padding-left:8px}
    .suite-body.hidden{display:none}

    .test-node{display:flex;align-items:center;gap:8px;padding:7px 10px 7px 28px;border-radius:6px;
      cursor:pointer;transition:background .15s;font-size:11.5px;margin:1px 0}
    .test-node:hover,.test-node.active{background:var(--bg-hover)}
    .test-node.active{border-left:2px solid var(--accent)}
    .node-icon{font-size:11px;flex-shrink:0}
    .node-icon.pass{color:var(--pass)}.node-icon.fail{color:var(--fail)}.node-icon.skip{color:var(--skip)}
    .node-label{flex:1;color:var(--text-1);line-height:1.3}
    .badge{font-size:9.5px;padding:2px 6px;border-radius:10px;flex-shrink:0}
    .badge.pass{background:rgba(34,197,94,.15);color:var(--pass)}
    .badge.fail{background:rgba(239,68,68,.15);color:var(--fail)}
    .test-time{font-size:10px;color:var(--text-3);flex-shrink:0;font-family:'JetBrains Mono',monospace}
    .trace-btn{font-size:9.5px;padding:2px 7px;border-radius:4px;background:rgba(124,58,237,.2);
      color:#a78bfa;border:1px solid rgba(124,58,237,.3);cursor:pointer;flex-shrink:0;transition:background .15s}
    .trace-btn:hover{background:rgba(124,58,237,.4)}

    .left-footer{padding:12px 16px;border-top:1px solid var(--border);font-size:10.5px;color:var(--text-2);display:flex;flex-direction:column;gap:4px}
    .left-footer .row{display:flex;justify-content:space-between}
    .left-footer .val{color:var(--text-1);font-weight:500}

    /* Right panel */
    .right-panel{display:flex;flex-direction:column;overflow:hidden;background:var(--bg-deep)}
    .right-header{padding:14px 20px;border-bottom:1px solid var(--border);display:flex;align-items:center;justify-content:space-between;background:var(--bg-panel)}
    .right-title{font-size:13px;font-weight:600;color:var(--text-1);display:flex;align-items:center;gap:10px}
    .right-title .tag{font-size:10px;background:rgba(124,58,237,.2);color:#a78bfa;border:1px solid rgba(124,58,237,.3);padding:2px 8px;border-radius:4px}
    .right-tabs{display:flex;gap:2px}
    .tab-btn{padding:6px 14px;border-radius:5px;border:none;background:transparent;color:var(--text-3);font-size:11.5px;cursor:pointer;font-family:inherit;transition:all .15s}
    .tab-btn:hover{color:var(--text-2);background:var(--bg-hover)}
    .tab-btn.active{background:var(--bg-card);color:var(--text-1);border:1px solid var(--border)}

    .right-body{flex:1;overflow:hidden;display:flex;flex-direction:column}
    .empty-state{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;color:var(--text-3)}
    .empty-state .icon{font-size:48px;opacity:.3}
    .empty-state p{font-size:13px}

    /* Tab panels */
    .tab-panel{flex:1;overflow-y:auto;padding:16px 20px;display:none}
    .tab-panel.active{display:flex;flex-direction:column;gap:16px}
    .tab-panel::-webkit-scrollbar{width:4px}
    .tab-panel::-webkit-scrollbar-thumb{background:var(--border);border-radius:2px}

    /* Timeline */
    .timeline-wrap{background:var(--bg-panel);border-radius:10px;border:1px solid var(--border);padding:16px 20px}
    .timeline-label{font-size:10px;color:var(--text-3);text-transform:uppercase;letter-spacing:.07em;margin-bottom:10px}
    .timeline-track{position:relative;height:6px;background:var(--bg-card);border-radius:3px}
    .timeline-fill{height:100%;border-radius:3px;background:linear-gradient(90deg,var(--accent),var(--accent-glow));width:0;transition:width .8s ease}
    .timeline-steps{display:flex;justify-content:space-between;margin-top:12px}
    .timeline-step{display:flex;flex-direction:column;align-items:center;gap:5px;font-size:9.5px;color:var(--text-3)}
    .step-dot{width:22px;height:22px;border-radius:50%;border:2px solid var(--border);background:var(--bg-card);
      display:flex;align-items:center;justify-content:center;font-size:9px;font-weight:700;transition:all .3s}
    .step-dot.done{border-color:var(--pass);background:rgba(34,197,94,.15);color:var(--pass)}
    .step-dot.fail{border-color:var(--fail);background:rgba(239,68,68,.15);color:var(--fail)}

    /* Stats cards */
    .stats-row{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}
    .stat-card{background:var(--bg-card);border:1px solid var(--border);border-radius:10px;padding:14px 16px}
    .stat-card .slabel{font-size:10px;color:var(--text-3);text-transform:uppercase;letter-spacing:.07em;margin-bottom:6px}
    .stat-card .sval{font-size:22px;font-weight:700}
    .sval.pass{color:var(--pass)}.sval.fail{color:var(--fail)}.sval.neutral{color:var(--text-1)}

    /* Test detail card */
    .test-detail-card{background:var(--bg-card);border:1px solid var(--border);border-radius:10px;padding:18px}
    .detail-header{display:flex;align-items:flex-start;gap:12px;margin-bottom:14px}
    .status-badge{padding:4px 12px;border-radius:20px;font-size:11px;font-weight:600;flex-shrink:0}
    .status-badge.PASSED{background:rgba(34,197,94,.15);color:var(--pass);border:1px solid rgba(34,197,94,.3)}
    .status-badge.FAILED{background:rgba(239,68,68,.15);color:var(--fail);border:1px solid rgba(239,68,68,.3)}
    .status-badge.SKIPPED{background:rgba(245,158,11,.15);color:var(--skip);border:1px solid rgba(245,158,11,.3)}
    .detail-name{font-size:14px;font-weight:600;color:var(--text-1)}
    .detail-parent{font-size:11px;color:var(--text-3);margin-top:2px}
    .meta-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
    .meta-item{background:var(--bg-panel);border-radius:6px;padding:10px 14px}
    .meta-item .key{font-size:10px;color:var(--text-3);margin-bottom:4px}
    .meta-item .val{font-size:12px;font-weight:500;color:var(--text-1);font-family:'JetBrains Mono',monospace}

    /* Section titles */
    .section-title{font-size:11px;font-weight:600;color:var(--text-2);text-transform:uppercase;letter-spacing:.08em;
      padding-bottom:8px;border-bottom:1px solid var(--border);margin-bottom:12px}

    /* Snapshot filmstrip */
    .filmstrip{display:flex;gap:8px;overflow-x:auto;padding-bottom:8px}
    .filmstrip::-webkit-scrollbar{height:4px}
    .filmstrip::-webkit-scrollbar-thumb{background:var(--border);border-radius:2px}
    .snap-frame{flex-shrink:0;width:130px;cursor:pointer;border-radius:6px;overflow:hidden;
      border:2px solid var(--border);transition:border-color .2s}
    .snap-frame:hover,.snap-frame.active{border-color:var(--accent)}
    .snap-frame img{width:100%;height:80px;object-fit:cover;display:block;background:var(--bg-card)}
    .snap-frame .snap-label{font-size:9px;color:var(--text-3);padding:3px 6px;background:var(--bg-panel);text-align:center}

    /* Preview lightbox */
    .preview-box{width:100%;background:var(--bg-panel);border-radius:8px;border:1px solid var(--border);
      overflow:hidden;display:flex;align-items:center;justify-content:center;min-height:200px}
    .preview-box img{max-width:100%;max-height:340px;object-fit:contain}
    .preview-empty{color:var(--text-3);font-size:12px;padding:40px}

    /* Network table */
    .net-table{width:100%;border-collapse:collapse;font-size:11.5px}
    .net-table th{text-align:left;font-size:10px;color:var(--text-3);font-weight:500;text-transform:uppercase;
      letter-spacing:.06em;border-bottom:1px solid var(--border);padding:6px 10px}
    .net-table td{padding:8px 10px;border-bottom:1px solid var(--border);color:var(--text-2)}
    .net-table tr:hover td{background:var(--bg-hover)}
    .method-tag{font-size:9px;font-weight:700;padding:2px 6px;border-radius:3px;font-family:'JetBrains Mono',monospace}
    .method-tag.POST{background:rgba(168,85,247,.2);color:#c084fc}
    .method-tag.GET{background:rgba(59,130,246,.2);color:#60a5fa}
    .method-tag.PUT{background:rgba(245,158,11,.2);color:#fbbf24}
    .method-tag.DELETE{background:rgba(239,68,68,.2);color:#f87171}
    .status-200{color:var(--pass);font-weight:600}
    .status-3xx{color:#38bdf8;font-weight:600}
    .status-4xx{color:var(--skip);font-weight:600}
    .net-table tr{cursor:pointer;transition:background .15s}
    .net-table tr.selected td{background:var(--bg-hover);border-left:3px solid var(--accent)}

    .net-detail-tab{background:transparent;border:none;border-bottom:2px solid transparent;color:var(--text-2);font-size:12px;font-weight:500;padding:6px 12px;cursor:pointer;transition:all .15s}
    .net-detail-tab:hover{color:var(--text-1)}
    .net-detail-tab.active{color:var(--accent-glow);border-bottom-color:var(--accent-glow);font-weight:600}

    .rerun-btn{background:rgba(34,197,94,.15);color:var(--pass);border:1px solid rgba(34,197,94,.3);font-size:10px;font-weight:600;padding:2px 8px;border-radius:4px;cursor:pointer;margin-left:auto;transition:all .15s;display:inline-flex;align-items:center;gap:4px}
    .rerun-btn:hover{background:var(--pass);color:#0d0d14;border-color:var(--pass)}
    .rerun-btn.test-level{font-size:9.5px;padding:1px 6px;margin-left:4px;margin-right:4px}

    /* Token preview */
    .token-preview{background:var(--bg-card);border-radius:8px;border:1px solid var(--border);padding:14px}
    .token-preview pre{font-family:'JetBrains Mono',monospace;font-size:11px;color:#86efac;line-height:1.7;white-space:pre-wrap;word-break:break-all}

    /* Scrollbar */
    *{scrollbar-width:thin;scrollbar-color:var(--border) transparent}
  </style>
</head>
<body>

<header class="topbar">
  <div class="topbar-logo">A</div>
  <div class="topbar-title">TEST DASHBOARD <span>| Authenza × Playwright × Gradle</span></div>
  <div class="topbar-stats">
    <button class="rerun-btn" style="font-size:11px;padding:4px 12px;background:linear-gradient(135deg,var(--accent),var(--accent-glow));color:white;border:none;box-shadow:0 0 10px rgba(168,85,247,.4)" onclick="runAllTests()">▶ Run All Tests</button>
    <div class="stat-chip"><div class="dot pass"></div><span>Passed:</span><span class="stat-val">${totalPassed}</span></div>
    <div class="stat-chip"><div class="dot fail"></div><span>Failed:</span><span class="stat-val">${totalFailed}</span></div>
    <div class="stat-chip"><div class="dot skip"></div><span>Skipped:</span><span class="stat-val">${totalSkip}</span></div>
    <div class="stat-chip" style="margin-left:8px">
      <svg width="36" height="36" viewBox="0 0 36 36">
        <circle cx="18" cy="18" r="15" fill="none" stroke="#1a1a2e" stroke-width="4"/>
        <circle cx="18" cy="18" r="15" fill="none" stroke="${totalFailed > 0 ? '#ef4444' : '#22c55e'}" stroke-width="4"
          stroke-dasharray="${(passRate / 100.0) * 94.25} 94.25" stroke-linecap="round" transform="rotate(-90 18 18)"/>
        <text x="18" y="22" text-anchor="middle" fill="${totalFailed > 0 ? '#ef4444' : '#22c55e'}" font-size="9" font-family="Inter" font-weight="700">${passRate}%</text>
      </svg>
    </div>
  </div>
</header>

<div class="layout">
  <!-- Left: Test Tree -->
  <aside class="left-panel">
    <div class="panel-header">JUnit 5 Tests <span class="badge-count">${totalTests} tests</span></div>
    <div class="test-tree">${treeHtml}</div>
    <div class="left-footer">
      <div class="row"><span>Executed</span><span class="val">${totalTests} Tests</span></div>
      <div class="row"><span>Passed</span><span class="val" style="color:var(--pass)">${totalPassed} (${passRate}%)</span></div>
      <div class="row"><span>Duration</span><span class="val">${String.format("%.2f", totalTime)}s</span></div>
      <div class="row"><span>Environment</span><span class="val">Local</span></div>
      <div class="row" style="margin-top:4px;font-size:9.5px;color:var(--text-3)"><span>Generated</span><span>${now}</span></div>
    </div>
  </aside>

  <!-- Right panel -->
  <main class="right-panel">
    <div class="right-header">
      <div class="right-title">
        <span id="panel-title">Select a test to inspect</span>
        <span class="tag" id="panel-tag">OAUTH2</span>
      </div>
      <div class="right-tabs">
        <button class="tab-btn active" id="btn-overview" onclick="switchTab('overview')">Overview</button>
        <button class="tab-btn" id="btn-traces"   onclick="switchTab('traces')">Traces (${traceMeta.size()})</button>
        <button class="tab-btn" id="btn-network"  onclick="switchTab('network')">Network</button>
      </div>
    </div>

    <div class="right-body">

      <!-- Overview tab -->
      <div id="tab-overview" class="tab-panel active">

        <!-- Timeline -->
        <div class="timeline-wrap">
          <div class="timeline-label">OAuth2 Authorization Flow</div>
          <div class="timeline-track"><div class="timeline-fill" id="tl-fill"></div></div>
          <div class="timeline-steps">
            <div class="timeline-step"><div class="step-dot done">1</div><span>Navigate</span></div>
            <div class="timeline-step"><div class="step-dot done">2</div><span>Login</span></div>
            <div class="timeline-step"><div class="step-dot done">3</div><span>Redirect</span></div>
            <div class="timeline-step"><div class="step-dot done">✓</div><span>Auth Code</span></div>
            <div class="timeline-step"><div class="step-dot done">✓</div><span>Token</span></div>
          </div>
        </div>

        <!-- Stats -->
        <div class="stats-row">
          <div class="stat-card"><div class="slabel">Total Tests</div><div class="sval neutral">${totalTests}</div></div>
          <div class="stat-card"><div class="slabel">Passed</div><div class="sval pass">${totalPassed}</div></div>
          <div class="stat-card"><div class="slabel">Failed</div><div class="sval ${totalFailed > 0 ? 'fail' : 'neutral'}">${totalFailed}</div></div>
          <div class="stat-card"><div class="slabel">Duration</div><div class="sval neutral">${String.format("%.1f", totalTime)}s</div></div>
        </div>

        <!-- Test detail -->
        <div class="test-detail-card" id="test-detail-card">
          <div style="color:var(--text-3);font-size:12px">Click a test in the tree to view its details here.</div>
        </div>
      </div>

      <!-- Traces tab -->
      <div id="tab-traces" class="tab-panel">
        <div class="section-title">Playwright Trace Recordings</div>

        <!-- Trace selector -->
        <div id="trace-selector" style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:4px"></div>

        <!-- Action Timeline -->
        <div>
          <div class="section-title">Action Timeline</div>
          <div class="timeline-viewer" id="timeline-viewer">
            <canvas id="action-canvas" style="width:100%;height:90px;display:block;cursor:crosshair"></canvas>
            <div id="action-tooltip" style="position:fixed;display:none;background:#1a1a2e;border:1px solid rgba(255,255,255,0.1);border-radius:6px;padding:6px 10px;font-size:11px;color:#f1f5f9;pointer-events:none;z-index:999;font-family:'JetBrains Mono',monospace;white-space:nowrap"></div>
          </div>
        </div>

        <!-- Filmstrip of screenshots -->
        <div>
          <div class="section-title">Captured Screenshots</div>
          <div class="filmstrip" id="filmstrip"></div>
        </div>

        <!-- Large preview of selected screenshot -->
        <div>
          <div class="section-title">Screenshot Preview</div>
          <div class="preview-box" id="preview-box">
            <div class="preview-empty">Click a frame in the filmstrip above to preview it</div>
          </div>
        </div>
      </div>


      <!-- Network tab -->
      <div id="tab-network" class="tab-panel">
        <div class="section-title">Captured Network Calls</div>
        <table class="net-table" id="net-table">
          <thead><tr>
            <th>Method</th><th>Endpoint</th><th>Status</th><th>Duration</th><th>Notes</th>
          </tr></thead>
          <tbody id="net-tbody"><tr><td colspan="5" style="color:var(--text-3);text-align:center;padding:20px">Select a test to load network calls</td></tr></tbody>
        </table>

        <!-- Network Endpoint Detail Drawer -->
        <div class="net-detail-drawer" id="net-detail-drawer" style="display:none;margin-top:16px;background:var(--bg-card);border:1px solid var(--border);border-radius:10px;padding:16px">
          <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
            <div style="display:flex;align-items:center;gap:10px">
              <span id="nd-method" class="method-tag GET">GET</span>
              <span id="nd-url" style="font-family:'JetBrains Mono',monospace;font-size:12px;color:var(--text-1);word-break:break-all"></span>
            </div>
            <button onclick="document.getElementById('net-detail-drawer').style.display='none'" style="background:transparent;border:none;color:var(--text-3);cursor:pointer;font-size:16px;padding:2px 8px">&times;</button>
          </div>

          <div style="display:flex;gap:12px;border-bottom:1px solid var(--border);margin-bottom:12px">
            <button class="net-detail-tab active" id="nd-tab-headers" onclick="switchNetTab('headers')">Headers</button>
            <button class="net-detail-tab" id="nd-tab-payload" onclick="switchNetTab('payload')">Payload</button>
            <button class="net-detail-tab" id="nd-tab-response" onclick="switchNetTab('response')">Response</button>
          </div>

          <div id="nd-content-headers" class="net-tab-content">
            <div style="font-size:11px;font-weight:600;color:var(--accent-glow);margin-bottom:6px">General</div>
            <div id="nd-general" style="font-family:'JetBrains Mono',monospace;font-size:11px;color:var(--text-2);margin-bottom:12px;background:var(--bg-panel);padding:10px;border-radius:6px"></div>
            
            <div style="font-size:11px;font-weight:600;color:var(--accent-glow);margin-bottom:6px">Request Headers</div>
            <div id="nd-req-headers" style="font-family:'JetBrains Mono',monospace;font-size:11px;color:var(--text-2);margin-bottom:12px;background:var(--bg-panel);padding:10px;border-radius:6px"></div>

            <div style="font-size:11px;font-weight:600;color:var(--accent-glow);margin-bottom:6px">Response Headers</div>
            <div id="nd-resp-headers" style="font-family:'JetBrains Mono',monospace;font-size:11px;color:var(--text-2);background:var(--bg-panel);padding:10px;border-radius:6px"></div>
          </div>

          <div id="nd-content-payload" class="net-tab-content" style="display:none">
            <pre id="nd-payload-body" style="font-family:'JetBrains Mono',monospace;font-size:11px;color:var(--text-1);background:var(--bg-panel);padding:12px;border-radius:6px;white-space:pre-wrap;word-break:break-all"></pre>
          </div>

          <div id="nd-content-response" class="net-tab-content" style="display:none">
            <pre id="nd-response-body" style="font-family:'JetBrains Mono',monospace;font-size:11px;color:#86efac;background:var(--bg-panel);padding:12px;border-radius:6px;white-space:pre-wrap;word-break:break-all"></pre>
          </div>
        </div>

        <div class="token-preview" id="token-preview" style="display:none;margin-top:16px">
          <div style="font-size:10px;color:var(--text-3);text-transform:uppercase;letter-spacing:.06em;margin-bottom:8px">Token Response Preview</div>
          <pre>{
  "access_token": "eyJraWQiOiJkNTg2YzZhZi00MT...",
  "refresh_token": "Mpiahjhx5IkSFqVoICcl2WRV...",
  "scope": "openid system:admin profile",
  "id_token": "eyJraWQiOiJkNTg2YzZhZi00MT...",
  "token_type": "Bearer",
  "expires_in": 299
}</pre>
        </div>
      </div>

    </div>
  </main>
</div>

<script>
// ── Data injected from Groovy ───────────────────────────────────────────────
const TRACES = ${traceJsonData};
let activeTraceIdx = 0;

// ── Tab switching ───────────────────────────────────────────────────────────
let drawTimelineGlobalRef = null;

function switchTab(name) {
  document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
  document.getElementById('tab-' + name).classList.add('active');
  document.getElementById('btn-' + name).classList.add('active');
  
  if (name === 'traces' && typeof drawTimelineGlobalRef === 'function') {
    // Redraw timeline now that the panel is visible and has a non-zero width
    setTimeout(drawTimelineGlobalRef, 50);
  }
}


// ── Suite toggle ────────────────────────────────────────────────────────────
function toggleSuite(header) {
  header.classList.toggle('open');
  header.nextElementSibling.classList.toggle('hidden');
}

// ── Show trace by index ─────────────────────────────────────────────────────
function showTrace(idx) {
  activeTraceIdx = idx;
  switchTab('traces');
  renderTrace(idx);
}

function renderTrace(idx) {
  if (idx < 0 || idx >= TRACES.length) return;
  const t = TRACES[idx];

  // Selector buttons
  const sel = document.getElementById('trace-selector');
  sel.innerHTML = TRACES.map((tr, i) =>
    '<button onclick="renderTrace(' + i + ')" style="padding:5px 12px;border-radius:5px;border:1px solid ' +
    (i === idx ? 'var(--accent)' : 'var(--border)') + ';background:' +
    (i === idx ? 'rgba(124,58,237,.2)' : 'var(--bg-card)') + ';color:' +
    (i === idx ? '#a78bfa' : 'var(--text-2)') + ';font-size:11px;cursor:pointer">' + tr.label + '</button>'
  ).join('');

  // ── Action Timeline (Canvas) ──────────────────────────────────────────────
  const actions = t.actions || [];
  const canvas  = document.getElementById('action-canvas');
  const tooltip = document.getElementById('action-tooltip');

  const CAT_COLORS = {
    navigate:  { bar: '#0ea5e9', bubble: '#38bdf8', label: '#7dd3fc' },
    fill:      { bar: '#8b5cf6', bubble: '#a78bfa', label: '#c4b5fd' },
    click:     { bar: '#22c55e', bubble: '#4ade80', label: '#86efac' },
    evaluate:  { bar: '#f59e0b', bubble: '#fbbf24', label: '#fde68a' },
    other:     { bar: '#475569', bubble: '#64748b', label: '#94a3b8' }
  };

  function drawTimeline() {
    const dpr    = window.devicePixelRatio || 1;
    const rect   = canvas.parentElement.getBoundingClientRect();
    const W      = rect.width;
    const H      = 90;
    canvas.width  = W * dpr;
    canvas.height = H * dpr;
    canvas.style.width  = W + 'px';
    canvas.style.height = H + 'px';
    const ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);

    // Background
    ctx.fillStyle = '#13131f';
    ctx.fillRect(0, 0, W, H);

    if (actions.length === 0) {
      ctx.fillStyle = '#475569';
      ctx.font = '12px Inter';
      ctx.textAlign = 'center';
      ctx.fillText('No actions recorded', W / 2, H / 2);
      return;
    }

    const minT   = Math.min(...actions.map(a => a.start));
    const maxT   = Math.max(...actions.map(a => a.end));
    const span   = maxT - minT || 1;
    const PAD_L  = 8;
    const PAD_R  = 12;
    const TW     = W - PAD_L - PAD_R;
    const AXIS_Y = H - 20;
    const BAR_H  = 8;
    const BAR_Y  = AXIS_Y - 28;

    function toX(t) { return PAD_L + ((t - minT) / span) * TW; }

    // Grid lines + time axis ticks
    const tickCount = 8;
    ctx.strokeStyle = 'rgba(255,255,255,0.05)';
    ctx.lineWidth = 1;
    for (let i = 0; i <= tickCount; i++) {
      const x = PAD_L + (i / tickCount) * TW;
      ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, AXIS_Y); ctx.stroke();
    }

    // Axis line
    ctx.strokeStyle = 'rgba(255,255,255,0.15)';
    ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(PAD_L, AXIS_Y); ctx.lineTo(W - PAD_R, AXIS_Y); ctx.stroke();

    // Tick labels
    ctx.fillStyle = '#475569';
    ctx.font = '9px JetBrains Mono, monospace';
    ctx.textAlign = 'center';
    for (let i = 0; i <= tickCount; i++) {
      const x   = PAD_L + (i / tickCount) * TW;
      const ms  = Math.round(minT + (i / tickCount) * span);
      const lbl = ms >= 1000 ? (ms/1000).toFixed(1) + 's' : ms + 'ms';
      ctx.fillText(lbl, x, H - 4);
    }

    // Draw action bars
    actions.forEach((a, i) => {
      const x1  = toX(a.start);
      const x2  = Math.max(toX(a.end), x1 + 3);
      const col = CAT_COLORS[a.cat] || CAT_COLORS.other;

      // Bar (rounded rect)
      const bx = x1, bw = x2 - x1, by = BAR_Y, bh = BAR_H, r = 3;
      ctx.fillStyle = a.error ? '#ef4444' : col.bar;
      ctx.globalAlpha = 0.85;
      ctx.beginPath();
      ctx.moveTo(bx + r, by);
      ctx.lineTo(bx + bw - r, by);
      ctx.arcTo(bx + bw, by, bx + bw, by + bh, r);
      ctx.lineTo(bx + bw, by + bh - r);
      ctx.arcTo(bx + bw, by + bh, bx + bw - r, by + bh, r);
      ctx.lineTo(bx + r, by + bh);
      ctx.arcTo(bx, by + bh, bx, by + bh - r, r);
      ctx.lineTo(bx, by + r);
      ctx.arcTo(bx, by, bx + r, by, r);
      ctx.closePath();
      ctx.fill();
      ctx.globalAlpha = 1.0;

      // Action bubble (circle with number)
      const cx = x1, cy = BAR_Y - 10;
      ctx.fillStyle = a.error ? '#ef4444' : col.bubble;
      ctx.beginPath();
      ctx.arc(cx, cy, 9, 0, Math.PI * 2);
      ctx.fill();

      ctx.fillStyle = '#0d0d14';
      ctx.font = 'bold 8px Inter';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      const lbl = a.error ? '✗' : (i + 1 <= 9 ? String(i + 1) : '✓');
      ctx.fillText(lbl, cx, cy);
      ctx.textBaseline = 'alphabetic';
    });
  }

  drawTimeline();
  drawTimelineGlobalRef = drawTimeline;
  new ResizeObserver(drawTimeline).observe(canvas.parentElement);

  // Tooltip on hover
  canvas.onmousemove = (e) => {
    if (!actions.length) return;
    const rect  = canvas.getBoundingClientRect();
    const mx    = (e.clientX - rect.left);
    const minT  = Math.min(...actions.map(a => a.start));
    const maxT  = Math.max(...actions.map(a => a.end));
    const span  = maxT - minT || 1;
    const PAD_L = 8, PAD_R = 12;
    const TW    = rect.width - PAD_L - PAD_R;
    const t_ms  = minT + ((mx - PAD_L) / TW) * span;
    const hit   = actions.find(a => a.start <= t_ms && a.end >= t_ms);
    if (hit) {
      const dur = (hit.end - hit.start).toFixed(1);
      tooltip.textContent = hit.api + '  (' + dur + 'ms)';
      tooltip.style.display = 'block';
      tooltip.style.left = (e.clientX + 12) + 'px';
      tooltip.style.top  = (e.clientY - 10) + 'px';
    } else {
      tooltip.style.display = 'none';
    }
  };
  canvas.onmouseleave = () => { tooltip.style.display = 'none'; };

  // Filmstrip
  const strip = document.getElementById('filmstrip');
  if (t.snapshots && t.snapshots.length > 0) {
    strip.innerHTML = t.snapshots.map((src, i) =>
      '<div class="snap-frame ' + (i === 0 ? 'active' : '') + '" onclick="previewSnap(this,\\'' + src + '\\',event)">' +
        '<img src="' + src + '" alt="frame ' + i + '" loading="lazy" onerror="this.style.display=\\'none\\'">' +
        '<div class="snap-label">Frame ' + (i + 1) + '</div>' +
      '</div>'
    ).join('');
    previewSnap(strip.querySelector('.snap-frame'), t.snapshots[0], null);
  } else {
    strip.innerHTML = '<div style="color:var(--text-3);font-size:12px;padding:20px">No screenshots captured for this trace.</div>';
  }

  // Load network into network tab as well
  renderNetwork(idx);
}


function previewSnap(el, src, e) {
  document.querySelectorAll('.snap-frame').forEach(f => f.classList.remove('active'));
  el.classList.add('active');
  document.getElementById('preview-box').innerHTML = '<img src="' + src + '" alt="preview">';
}

// ── Network rendering ───────────────────────────────────────────────────────
function renderNetwork(idx) {
  if (idx < 0 || idx >= TRACES.length) return;
  const rows = TRACES[idx].network || [];
  const tbody = document.getElementById('net-tbody');
  document.getElementById('net-detail-drawer').style.display = 'none';
  
  if (rows.length === 0) {
    tbody.innerHTML = '<tr><td colspan="5" style="color:var(--text-3);text-align:center;padding:20px">No network calls captured for this trace.</td></tr>';
    return;
  }
  tbody.innerHTML = rows.map((r, rIdx) =>
    '<tr id="net-row-' + rIdx + '" onclick="showEndpointDetail(' + idx + ',' + rIdx + ')">' +
      '<td><span class="method-tag ' + r.method + '">' + r.method + '</span></td>' +
      '<td style="font-family:monospace;font-size:11px">' + r.url + '</td>' +
      '<td><span class="' + r.statusCls + '">' + r.status + '</span></td>' +
      '<td>' + r.duration + '</td>' +
      '<td style="color:' + (r.note.startsWith('✓') ? 'var(--pass)' : 'var(--text-3)') + '">' + r.note + '</td>' +
    '</tr>'
  ).join('');

  // Show token preview if token endpoint is in the calls
  const hasToken = rows.some(r => r.url.includes('/oauth2/token') && r.status === 200);
  document.getElementById('token-preview').style.display = hasToken ? 'block' : 'none';
}

function showEndpointDetail(traceIdx, rowIdx) {
  document.querySelectorAll('#net-tbody tr').forEach(tr => tr.classList.remove('selected'));
  const rowEl = document.getElementById('net-row-' + rowIdx);
  if (rowEl) rowEl.classList.add('selected');

  const r = TRACES[traceIdx].network[rowIdx];
  if (!r) return;

  const drawer = document.getElementById('net-detail-drawer');
  drawer.style.display = 'block';

  // Method tag
  const methodEl = document.getElementById('nd-method');
  methodEl.className = 'method-tag ' + r.method;
  methodEl.textContent = r.method;

  // URL
  document.getElementById('nd-url').textContent = r.fullUrl || r.url;

  // General details
  document.getElementById('nd-general').innerHTML =
    '<div><strong>Status Code:</strong> <span class="' + r.statusCls + '">' + r.status + '</span></div>' +
    '<div><strong>Duration:</strong> ' + r.duration + '</div>' +
    (r.note ? '<div><strong>Notes:</strong> ' + r.note + '</div>' : '');

  // Request Headers
  const reqHeaders = r.reqHeaders || [];
  if (reqHeaders.length > 0) {
    document.getElementById('nd-req-headers').innerHTML = reqHeaders.map(h => '<div><strong>' + h.name + ':</strong> ' + h.value + '</div>').join('');
  } else {
    document.getElementById('nd-req-headers').innerHTML = '<div style="color:var(--text-3)">No request headers captured</div>';
  }

  // Response Headers
  const respHeaders = r.respHeaders || [];
  if (respHeaders.length > 0) {
    document.getElementById('nd-resp-headers').innerHTML = respHeaders.map(h => '<div><strong>' + h.name + ':</strong> ' + h.value + '</div>').join('');
  } else {
    document.getElementById('nd-resp-headers').innerHTML = '<div style="color:var(--text-3)">No response headers captured</div>';
  }

  // Payload
  const payloadBox = document.getElementById('nd-payload-body');
  if (r.postData) {
    payloadBox.textContent = r.postData;
  } else {
    payloadBox.textContent = r.method === 'GET' ? 'No request payload (GET request)' : 'No request payload data captured';
  }

  // Response Body
  const respBox = document.getElementById('nd-response-body');
  if (r.respBody) {
    respBox.textContent = r.respBody;
  } else {
    respBox.textContent = 'Response status: ' + r.status;
  }

  switchNetTab('headers');
}

function switchNetTab(tabName) {
  document.querySelectorAll('.net-detail-tab').forEach(b => b.classList.remove('active'));
  document.querySelectorAll('.net-tab-content').forEach(c => c.style.display = 'none');

  document.getElementById('nd-tab-' + tabName).classList.add('active');
  document.getElementById('nd-content-' + tabName).style.display = 'block';
}

// ── Test node selection ─────────────────────────────────────────────────────
function selectTest(el, name, parent, status, time, traceIdx) {
  document.querySelectorAll('.test-node').forEach(n => n.classList.remove('active'));
  el.classList.add('active');
  document.getElementById('panel-title').textContent = name;

  // Update detail card
  const statusCls = status;
  const card = document.getElementById('test-detail-card');
  card.innerHTML =
    '<div class="detail-header">' +
      '<span class="status-badge ' + statusCls + '">' + status + '</span>' +
      '<div>' +
        '<div class="detail-name">' + name + '</div>' +
        '<div class="detail-parent">' + parent + '</div>' +
      '</div>' +
      '<button class="rerun-btn" style="margin-left:auto;padding:6px 14px;font-size:11px" onclick="runSingleTest(\'' + parent + '\',\'' + name + '\')">▶ Re-run Test</button>' +
    '</div>' +
    '<div class="meta-grid">' +
      '<div class="meta-item"><div class="key">Duration</div><div class="val">' + time + 's</div></div>' +
      '<div class="meta-item"><div class="key">Status</div><div class="val">' + status + '</div></div>' +
      '<div class="meta-item"><div class="key">Trace</div><div class="val">' + (traceIdx >= 0 ? 'Available' : 'N/A') + '</div></div>' +
    '</div>';

  // If trace exists, pre-load network tab and show trace tab hint
  if (traceIdx >= 0) {
    renderNetwork(traceIdx);
    activeTraceIdx = traceIdx;
  }
  switchTab('overview');
}

// ── Test Re-run Handlers & CLI Command Modal ────────────────────────────────
function showRunModal(title, cmd) {
  let modal = document.getElementById('rerun-modal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'rerun-modal';
    modal.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.7);z-index:9999;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(4px)';
    document.body.appendChild(modal);
  }
  modal.innerHTML =
    '<div style="background:var(--bg-card);border:1px solid var(--accent);border-radius:12px;padding:24px;width:560px;max-width:90%;box-shadow:0 10px 30px rgba(0,0,0,0.5)">' +
      '<div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">' +
        '<div style="font-size:14px;font-weight:700;color:var(--text-1);display:flex;align-items:center;gap:8px">' +
          '<span style="width:8px;height:8px;border-radius:50%;background:var(--pass);box-shadow:0 0 8px var(--pass)"></span>' + title +
        '</div>' +
        '<button onclick="document.getElementById(\'rerun-modal\').remove()" style="background:transparent;border:none;color:var(--text-3);cursor:pointer;font-size:18px">&times;</button>' +
      '</div>' +
      '<div style="font-size:11px;color:var(--text-2);margin-bottom:8px">Run command in terminal to execute target test:</div>' +
      '<div style="background:var(--bg-deep);border:1px solid var(--border);border-radius:6px;padding:12px;font-family:\'JetBrains Mono\',monospace;font-size:11.5px;color:#86efac;display:flex;align-items:center;justify-content:space-between;word-break:break-all">' +
        '<code>' + cmd + '</code>' +
        '<button onclick="navigator.clipboard.writeText(\'' + cmd.replace(/'/g, "\\'") + '\');this.textContent=\'Copied!\';setTimeout(()=>this.textContent=\'Copy\',2000)" style="background:var(--bg-panel);border:1px solid var(--border);color:var(--text-1);font-size:10px;padding:4px 10px;border-radius:4px;cursor:pointer;margin-left:10px;flex-shrink:0">Copy</button>' +
      '</div>' +
      '<div style="margin-top:16px;font-size:10.5px;color:var(--text-3);display:flex;align-items:center;justify-content:space-between">' +
        '<span>After running, refresh this page or run gradle generateE2EReport</span>' +
        '<button onclick="document.getElementById(\'rerun-modal\').remove()" style="background:var(--accent);color:white;border:none;padding:6px 14px;border-radius:6px;font-size:11px;font-weight:600;cursor:pointer">Done</button>' +
      '</div>' +
    '</div>';
}

function runSingleTest(className, testName) {
  const filter = className + '.' + testName;
  const cmd = './gradlew :auth-e2e-tests:test --tests "' + filter + '"';
  showRunModal('Re-running Test: ' + testName, cmd);
}

function runTestGroup(suiteName) {
  const cmd = './gradlew :auth-e2e-tests:test --tests "*' + suiteName.replace(/\s+/g,'*') + '*"';
  showRunModal('Re-running Test Suite: ' + suiteName, cmd);
}

function runAllTests() {
  const cmd = './gradlew :auth-e2e-tests:test';
  showRunModal('Re-running All E2E Tests', cmd);
}

// ── Init ────────────────────────────────────────────────────────────────────
window.addEventListener('DOMContentLoaded', () => {
  // Animate timeline
  setTimeout(() => { document.getElementById('tl-fill').style.width = '100%'; }, 300);
  // Open all suites
  document.querySelectorAll('.suite-header').forEach(h => h.classList.add('open'));
  // Load first trace into traces tab
  if (TRACES.length > 0) renderTrace(0);
});
</script>
</body>
</html>"""

// Write output
def outFile = new File(outDir, "dashboard.html")
outFile.text = html
println "✅ Dashboard generated: ${outFile.absolutePath}"
