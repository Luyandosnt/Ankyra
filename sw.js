// ANKYRA v1 — Service Worker
const CACHE = 'ankyra-v1';

self.addEventListener('install', e => {
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(clients.claim());
});

// Handle push notifications from FCM
self.addEventListener('push', e => {
  const data = e.data ? e.data.json() : {};
  e.waitUntil(
    self.registration.showNotification(data.title || 'ANKYRA v1', {
      body: data.body || '',
      icon: data.icon || './icon-192.png',
      badge: './icon-192.png',
      tag: data.tag || 'ankyra',
      renotify: true,
      vibrate: [200, 100, 200],
      data: { url: data.url || './' }
    })
  );
});

// Tap notification → open/focus the app
self.addEventListener('notificationclick', e => {
  e.notification.close();
  const url = e.notification.data?.url || './';
  e.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then(list => {
      const existing = list.find(c => c.url.includes('Ankyra'));
      if (existing) return existing.focus();
      return clients.openWindow(url);
    })
  );
});

// Allow main thread to trigger notifications directly (for foreground → background transitions)
self.addEventListener('message', e => {
  if (e.data?.type === 'ZONE_CHANGE') {
    self.registration.showNotification(e.data.title, {
      body: e.data.body,
      tag: 'zone-change',
      renotify: true,
      vibrate: [150, 80, 150],
      icon: './icon-192.png',
    });
  }
});
