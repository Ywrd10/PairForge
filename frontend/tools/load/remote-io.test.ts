import { EventEmitter } from 'node:events'
import { PassThrough } from 'node:stream'
import { afterEach, expect, it, vi } from 'vitest'
const state=vi.hoisted(()=>({children:[] as unknown[]}))
vi.mock('node:child_process',()=>({execFile:vi.fn(),spawn:vi.fn(()=>{
  const child=Object.assign(new EventEmitter(),{stdout:new PassThrough(),stderr:new PassThrough(),exitCode:0,pid:undefined,kill:vi.fn()})
  state.children.push(child);return child
})}))
import { observeHosts } from './remote-io.ts'
const sample=(role:string)=>JSON.stringify({role,cpuPercent:20,availableBytes:2**30,diskAvailableBytes:10*2**30,diskTotalBytes:30*2**30,oomKills:0,cleanupFailures:0,healthy:true,ready:0,unacked:0})+'\n'
afterEach(()=>{state.children=[];vi.useRealTimers()})
it('consumes both private host streams, enforces freshness, and closes readers on failure',async()=>{
  vi.useFakeTimers();const abort=new AbortController(),watch=observeHosts('pwsh',abort)
  const children=state.children as (EventEmitter & {stdout:PassThrough})[]
  children[0].stdout.write(sample('App'));children[1].stdout.write(sample('Worker'))
  watch.startGuard();expect(watch.samples).toHaveLength(2)
  await vi.advanceTimersByTimeAsync(21000)
  expect(abort.signal.aborted).toBe(true);expect(watch.failures).toEqual(['Missing or stale capacity observation'])
  await watch.stop();expect(vi.getTimerCount()).toBe(0)
})
it('aborts on malformed or incorrectly identified measurements',async()=>{
  const abort=new AbortController(),watch=observeHosts('pwsh',abort)
  const child=state.children[0] as {stdout:PassThrough}
  child.stdout.write(sample('Worker'))
  expect(abort.signal.aborted).toBe(true)
  await watch.stop()
})
it('aborts when an observer exits before completion but not during deliberate cleanup',async()=>{
  const abort=new AbortController(),watch=observeHosts('pwsh',abort)
  ;(state.children[0] as EventEmitter).emit('exit',1)
  expect(abort.signal.aborted).toBe(true);await watch.stop()
  const other=new AbortController(),closed=observeHosts('pwsh',other)
  await closed.stop();(state.children[2] as EventEmitter).emit('exit',0)
  expect(other.signal.aborted).toBe(false)
})
