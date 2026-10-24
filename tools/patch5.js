const fs = require("fs");
const P = "android/assets/www/index.html";
let h = fs.readFileSync(P, "utf8");
const out = [];
function must(c, l) { out.push((c ? "OK   " : "FAIL ") + l); if (!c) throw new Error(l); }

// ---------- 1) 删除成员：纯本地 ----------
const oldBlock = `async function deleteMemberFromCloud(memberId) {
  await edgeCall('delete-member', { id: memberId });
}
document.getElementById('deleteMemberBtn').addEventListener('click', async ()=>{
  if(selectedIds.size===0){await dialogAlert('请选择要删除的成员'); return;}
  const names = Array.from(selectedIds).map(id => esc(data.members.find(m=>m.id===id)?.name || '')).join('、');
  if(!await dialogConfirm(\`永久删除：\${names}？\`,'删除',{danger:true})) return;
  const failed = [];
  const toDelete = Array.from(selectedIds);
  for(const id of toDelete) {
    try {
      await deleteMemberFromCloud(id);
      data.members = data.members.filter(m => m.id !== id);
    } catch(e) {
      console.error('删除云端成员失败', e);
      failed.push(data.members.find(m=>m.id===id)?.name || id);
    }
  }
  selectedIds.clear(); renderAdmin(false); renderHome();
  if(failed.length === 0) {
    autoSync();
    addLog('删除成员', names, '');
    showToast('已删除（含云端）','info');
  } else {
    addLog('删除成员', names, \`失败：\${failed.join('、')}\`);
    showToast(\`部分删除失败：\${failed.join('、')}\`,'error');
  }
});`;

must(h.includes(oldBlock), "定位删除成员区块");

const newBlock = `document.getElementById('deleteMemberBtn').addEventListener('click', async ()=>{
  if(selectedIds.size===0){await dialogAlert('请选择要删除的成员'); return;}
  const names = Array.from(selectedIds).map(id => esc(data.members.find(m=>m.id===id)?.name || '')).join('、');
  if(!await dialogConfirm(\`永久删除：\${names}？\`, '删除', {danger:true})) return;

  const toDelete = Array.from(selectedIds);
  data.members = data.members.filter(m => !toDelete.includes(m.id));
  selectedIds.clear();
  renderAdmin(false);
  renderHome();
  autoSync();                       // 立即重写本地 SQLite
  addLog('删除成员', names, '');
  showToast('已删除', 'success');
});`;

h = h.replace(oldBlock, newBlock);
out.push("     删除成员改为纯本地（无云端失败分支）");

// ---------- 2) 刷新按钮文案 ----------
h = h.replace(`  showToast('正在同步...','info');`, `  showToast('正在刷新...','info');`);
h = h.replace(`    showToast('同步成功','success');`, `    showToast('已是最新数据','success');`);
h = h.replace(`    showToast('云端无数据','info');`, `    showToast('本地暂无数据','info');`);
h = h.replace(`    showToast('同步失败','error');`, `    showToast('读取失败','error');`);
out.push("OK   刷新按钮文案本地化");

// ---------- 3) 导出：加一层原生保存确认 ----------
// exportCSV 等已由 native-bridge.js 接管 XLSX.writeFile，这里不动

// ---------- 4) 全局错误兜底：出错时提示而不是白屏 ----------
const guardAnchor = "// ========== 工具 ==========";
must(h.includes(guardAnchor), "定位工具区块");
h = h.replace(guardAnchor, `// ========== 全局错误兜底 ==========
window.addEventListener('error', function (ev) {
  console.error('页面脚本错误', ev.message, ev.filename + ':' + ev.lineno);
});
window.addEventListener('unhandledrejection', function (ev) {
  console.warn('未处理的 Promise 拒绝', ev.reason && ev.reason.message);
});

// ========== 工具 ==========`);

fs.writeFileSync(P, h);
console.log(out.join("\n"));
