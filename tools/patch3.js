const fs = require("fs");
const P = "android/assets/www/index.html";
let h = fs.readFileSync(P, "utf8");

const anchor = "if (data.members.length) autoSync();";
const i = h.indexOf(anchor);
if (i < 0) throw new Error("找不到新 init 结束锚点");

// 从锚点后开始，第一个 "})();" 是新 init 的结尾
const m = /\}\)\(\);\r?\n/.exec(h.slice(i));
if (!m) throw new Error("找不到新 init 的 })();");
const tailStart = i + m.index + m[0].length;

// 之后到下一个 "})();" 之间是旧 init 的残尾
const rest = h.slice(tailStart);
const m2 = /\}\)\(\);\r?\n/.exec(rest);
if (!m2) throw new Error("找不到残尾结束点");
const leftover = rest.slice(0, m2.index + m2[0].length);

if (!leftover.includes("initialized")) {
  throw new Error("残尾内容异常，拒绝删除:\n" + leftover.slice(0, 300));
}

h = h.slice(0, tailStart) + rest.slice(m2.index + m2[0].length);

// 统一行尾为 LF
h = h.replace(/\r\n/g, "\n");

fs.writeFileSync(P, h);
console.log("已删除残留旧 init 代码", leftover.length, "字符，并统一为 LF");
