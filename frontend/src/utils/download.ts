/** 解析 Content-Disposition：优先 filename*=UTF-8''<百分号编码>（decodeURIComponent 解码），
 *  回退普通 filename="..."；无该头/无法解析返回 null。解码失败也回退普通 filename，避免吞掉可读名。 */
const STAR_PATTERN = /filename\*\s*=\s*UTF-8''([^;]+)/i;
const FALLBACK_PATTERN = /filename\s*=\s*"([^"]*)"/i;

function percentDecode(value: string): string | null {
  try {
    return decodeURIComponent(value);
  } catch {
    return null; // 畸形百分号序列
  }
}

export function parseDownloadFilename(contentDisposition?: string | null): string | null {
  if (!contentDisposition) return null;
  const star = STAR_PATTERN.exec(contentDisposition);
  if (star) {
    const decoded = percentDecode(star[1]);
    if (decoded != null) return decoded;
  }
  const plain = FALLBACK_PATTERN.exec(contentDisposition);
  return plain ? plain[1] : null;
}

/** 把 blob 以指定文件名触发浏览器保存并立即释放对象 URL */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
