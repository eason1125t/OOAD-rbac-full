// 文件职责：在Edge/Chromium验收中直接隔离认证SharedWorker网络；页面离线开关不覆盖该目标。
// 协作关系：仅由jwt.spec.ts故障测试使用CDP，不读取令牌、不改变生产认证逻辑。
import type { Browser } from '@playwright/test'

export async function isolateAuthWorkerNetwork(browser: Browser) {
  const protocol = await browser.newBrowserCDPSession()
  const { targetInfos } = await protocol.send('Target.getTargets')
  const targets = targetInfos.filter(target => target.type === 'shared_worker' && target.url.includes('/assets/auth.worker-'))
  if (targets.length !== 1) { await protocol.detach(); throw new Error('Expected one isolated browser auth worker') }
  const { sessionId } = await protocol.send('Target.attachToTarget', { targetId: targets[0].targetId, flatten: false })
  let sequence = 0
  async function command(method: string, params = {}) {
    const id = ++sequence
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => { cleanup(); reject(new Error('Worker network command timed out')) }, 5000)
      const listener = (event: { sessionId: string; message: string }) => {
        if (event.sessionId !== sessionId) return
        const reply = JSON.parse(event.message)
        if (reply.id !== id) return
        cleanup()
        if (reply.error) reject(new Error('Worker network command rejected'))
        else resolve()
      }
      function cleanup() { clearTimeout(timer); protocol.off('Target.receivedMessageFromTarget', listener) }
      protocol.on('Target.receivedMessageFromTarget', listener)
      void protocol.send('Target.sendMessageToTarget', { sessionId, message: JSON.stringify({ id, method, params }) })
        .catch(error => { cleanup(); reject(error) })
    })
  }
  await command('Network.enable')
  let closed = false
  return {
    setOffline: (offline: boolean) => closed ? Promise.resolve() : command('Network.emulateNetworkConditions',
      { offline, latency: 0, downloadThroughput: -1, uploadThroughput: -1 }),
    close: async () => { if (!closed) { closed = true; await protocol.detach() } },
  }
}
