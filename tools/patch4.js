const fs = require("fs");
const P = "android/assets/www/index.html";
let h = fs.readFileSync(P, "utf8");
const report = [];

function must(cond, label) { report.push((cond ? "OK   " : "FAIL ") + label); if (!cond) throw new Error("失败: " + label); }

// ---------- 1) 删掉 Supabase 常量 ----------
const cfgStart = h.indexOf("const SUPABASE_URL =");
const cfgEnd = h.indexOf("const DEFAULT_PASSWORD =");
must(cfgStart > 0 && cfgEnd > cfgStart, "定位 Supabase 配置");
h = h.slice(0, cfgStart) + h.slice(cfgEnd);
report.push("     已移除 SUPABASE_URL / SUPABASE_ANON_KEY / EDGE_FUNCTION_URL");

// ---------- 2) isOnline 固定为 true（本地模式永远\"在线\"） ----------
const onlineOld = "let isOnline = navigator.onLine;";
must(h.includes(onlineOld), "定位 isOnline");
h = h.replace(onlineOld, "let isOnline = true;   // 本地模式：数据始终可用");

const listenOld = [
"window.addEventListener('online', () => { isOnline = true; showToast('网络已恢复', 'success'); autoSync(); });",
"window.addEventListener('offline', () => { isOnline = false; showToast('已离线，修改仅保存在本地', 'info'); });"
].join("\n");
if (h.includes(listenOld)) {
  h = h.replace(listenOld, "// 本地模式：不监听网络状态");
  report.push("OK   已移除 online/offline 监听");
} else {
  report.push("SKIP online/offline 监听不在（可能已改）");
}

// ---------- 3) sendCloudLog 改为本地日志（无网络） ----------
const scStart = h.indexOf("async function sendCloudLog(action, target, detail) {");
const scEnd = h.indexOf("function addLog(action, target, detail) {");
must(scStart > 0 && scEnd > scStart, "定位 sendCloudLog");
h = h.slice(0, scStart) + h.slice(scEnd);
report.push("     已移除 sendCloudLog（日志本来就存 localStorage，不再需要）");

// addLog 内部调用 sendCloudLog 也要去掉
h = h.replace("  sendCloudLog(action, target, detail);\n", "");
report.push("OK   addLog 不再调用云端");

// ---------- 4) restoreBackup 恢复后必须落库 ----------
const restoreTail = "    showToast('已恢复本地（未上传）','success');";
must(h.includes(restoreTail), "定位 restoreBackup 尾部");
h = h.replace(restoreTail,
  "    autoSync();   // 本地模式：立即写入 SQLite\n" +
  "    showToast('已恢复到本地数据库','success');");

// 确认文案也改掉
h = h.replace("当前本地数据将被覆盖！<br><b>不会自动上传云端</b>，请确认后再手动同步。",
              "当前本地数据将被覆盖！");

// ---------- 5) 引导文案：\"请联网重试\" → 本地提示 ----------
const before = (h.match(/SheetJS 未加载，请联网重试/g) || []).length;
h = h.replace(/SheetJS 未加载，请联网重试/g, "导出模块未加载，请重启 App");
report.push("OK   已改导出失败提示 " + before + " 处");

// ---------- 6) 加载失败提示改为本地 ----------
h = h.replace("showToast('加载云端失败，使用本地默认（未上传）','error');",
              "showToast('加载本地数据失败，已使用默认成员','error');");
h = h.replace("showLoading('加载云端数据...','loading');", "showLoading('加载本地数据...','loading');");

fs.writeFileSync(P, h);
console.log(report.join("\n"));
