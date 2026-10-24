const fs = require("fs");
const P = "android/assets/www/index.html";
let h = fs.readFileSync(P, "utf8");
let changed = 0;

// ---------- 1) 去掉 online/offline 监听（本地模式无网络概念） ----------
const onlineOld = [
  "window.addEventListener('online', () => { isOnline = true; showToast('网络已恢复', 'success'); autoSync(); });",
  "window.addEventListener('offline', () => { isOnline = false; showToast('已离线，修改仅保存在本地', 'info'); });"
].join("\n");
const onlineNew = "// 本地模式：不监听网络状态，所有数据都在本机 SQLite 里。";
if (h.includes(onlineOld)) { h = h.replace(onlineOld, onlineNew); changed++; }
else console.warn("!! online 监听未找到");

// ---------- 2) 重写初始化流程 ----------
const initStart = h.indexOf("(async function init() {");
const initEnd = h.indexOf("})();", initStart);
if (initStart < 0 || initEnd < 0) throw new Error("init 定位失败");

const initNew = [
"(async function init() {",
"  try { auditLogs = JSON.parse(localStorage.getItem('scoreboard_admin_logs')||'[]'); } catch(e){ auditLogs = []; }",
"",
"  showLoading('加载本地数据...','loading');",
"  const res = await loadFromCloud();",
"",
"  if (!res.ok && res.empty) {",
"    // 数据库为空（极少数情况：种子未灌入）。用默认名单兜底。",
"    data.members = DEFAULT_MEMBERS.map((name, i) => ({",
"      id: 'u' + (i + 1), name, totalScore: 0, records: []",
"    }));",
"    showToast('本地暂无数据，已载入默认成员', 'info');",
"  } else if (!res.ok) {",
"    showToast('读取本地数据失败，请重启 App', 'error');",
"    data.members = [];",
"  }",
"",
"  initUI();",
"",
"  // 每次都监听窗口尺寸（原版只在无缓存分支注册，是有 bug 的）",
"  let navResizeTimer = null;",
"  window.addEventListener('resize', () => {",
"    clearTimeout(navResizeTimer);",
"    navResizeTimer = setTimeout(() => {",
"      const active = document.querySelector('.nav-btn.active');",
"      updateNavSlider(active, false);",
"    }, 80);",
"  });",
"",
"  // 启动时同步一次到 SQLite，保证种子数据落库",
"  if (data.members.length) autoSync();",
""].join("\n");

h = h.slice(0, initStart) + initNew + h.slice(initEnd);
changed++;

fs.writeFileSync(P, h);
console.log("已修改区块数:", changed);
