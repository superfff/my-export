import { useEffect, useState } from 'react';

/**
 * 基于原生 pushState / popState 的轻量路由。
 * navigate 修改历史记录后手动派发 popstate，驱动 useRouter 更新当前路径。
 */
export interface NavigateOptions {
  replace?: boolean;
}

export function navigate(path: string, options: NavigateOptions = {}) {
  if (window.location.pathname === path) return;
  if (options.replace) {
    window.history.replaceState(null, '', path);
  } else {
    window.history.pushState(null, '', path);
  }
  window.dispatchEvent(new PopStateEvent('popstate'));
}

export function useRouter() {
  const [path, setPath] = useState(() => window.location.pathname);

  useEffect(() => {
    const onPopState = () => setPath(window.location.pathname);
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  return { path, navigate };
}
