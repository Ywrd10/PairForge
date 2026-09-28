import { expect, it } from 'vitest'
import { approvedIdentity, approvedUsers, CapacityGuard, hostSample, privateSessions, remoteConfig, resumeBudget } from './remote-policy.ts'
import { configureRemoteTarget } from './transport.ts'
import local from './local.json'

const sample = (role: 'App' | 'Worker' = 'App') => hostSample({ role, cpuPercent: 20, availableBytes: 2**30,
  diskAvailableBytes: 10 * 2**30, diskTotalBytes: 30 * 2**30, oomKills: 3, cleanupFailures: 0, healthy: true, ready: 0, unacked: 0 })
it('rejects expired/malformed credentials without echoing private input',()=>{
  const sessions=[{accessToken:'secret',expiresAt:new Date(900000).toISOString()},{accessToken:'other',expiresAt:new Date(900000).toISOString()}]
  expect(privateSessions(JSON.stringify(sessions),0)).toHaveLength(2)
  for(const input of ['secret malformed',JSON.stringify([{...sessions[0],expiresAt:'invalid'},sessions[1]]),JSON.stringify(sessions)]) {
    try {privateSessions(input,300000);throw new Error('Should fail')}catch(e){expect((e as Error).message).toBe('Two fresh private sessions required; input is not logged')}
  }
})
it('accepts only the exact approved remote workload and target', () => {
  expect(remoteConfig(local).maxJobs).toBe(30)
  for (const change of [{maxJobs:31},{measurementSeconds:21},{stages:[2,10]},{maxDurationSeconds:901}]) expect(()=>remoteConfig({...local,...change})).toThrow()
  for (const url of ['http://d3pq3na8h2es74.cloudfront.net','https://example.com','https://d3pq3na8h2es74.cloudfront.net/']) expect(()=>configureRemoteTarget(url)).toThrow()
})
it('requires both distinct approved identities', () => {
  approvedIdentity([...approvedUsers].reverse())
  expect(()=>approvedIdentity([approvedUsers[0],approvedUsers[0]])).toThrow()
  expect(()=>approvedIdentity([approvedUsers[0],'other'])).toThrow()
})
it('resumes only verified completed work without resetting the original deadline',()=>{
  const checkpoint={settings:local,target:'https://d3pq3na8h2es74.cloudfront.net',started:new Date(0).toISOString(),
    failure:{phase:'execution-JAVA-repeat-1',reason:'Scenario or inspection failed; no uncertain submission retried'},
    collaboration:Array.from({length:9},()=>({passed:true,restored:true})),resourceErrors:[],
    execution:[{passed:true,submitted:5,accepted:5,uncertain:0,language:'JAVA',repetition:1,jobs:Array.from({length:5},()=>({status:'SUCCEEDED'}))}]}
  expect(resumeBudget(checkpoint,300000)).toBe(600000)
  expect(()=>resumeBudget(checkpoint,900000)).toThrow('deadline')
  expect(()=>resumeBudget({...checkpoint,resourceErrors:['health loss']},300000)).toThrow()
  expect(()=>resumeBudget({...checkpoint,execution:[{...checkpoint.execution[0],uncertain:1}]},300000)).toThrow()
  expect(()=>resumeBudget({...checkpoint,execution:[{...checkpoint.execution[0],submitted:6}]},300000)).toThrow()
  expect(()=>resumeBudget({...checkpoint,collaboration:[]},300000)).toThrow()
  expect(resumeBudget(checkpoint,1800000,0)).toBe(420000)
  expect(()=>resumeBudget(checkpoint,2400000,0)).toThrow('operating window')
  expect(()=>resumeBudget(checkpoint,1800000,NaN)).toThrow('operating window')
})
it('rejects malformed samples rather than substituting zero', () => {
  for (const change of [{cpuPercent:NaN},{healthy:'UP'},{diskTotalBytes:0},{ready:-1},{role:'other'}]) expect(()=>hostSample({...sample(),...change})).toThrow()
})
it('requires both fresh host observations', () => {
  const g=new CapacityGuard(); g.observe(sample(),0)
  expect(()=>g.fresh(0)).toThrow()
  g.observe(sample('Worker'),0); g.fresh(20000)
  expect(()=>g.fresh(20001)).toThrow()
})
it('stops for readiness, memory, both disk thresholds, OOM and cleanup failures', () => {
  for (const change of [{healthy:false},{availableBytes:256*2**20-1},{diskAvailableBytes:2**30},
    {diskTotalBytes:200*2**30},{oomKills:4},{cleanupFailures:1}]) {
    const g=new CapacityGuard(); g.observe(sample(),0)
    expect(()=>g.observe({...sample(),...change},1000)).toThrow()
  }
})
it('stops escalation at sixty seconds of sustained CPU but resets after recovery', () => {
  const g=new CapacityGuard(); const high={...sample(),cpuPercent:86}
  g.observe(high,0);g.observe(high,59999)
  expect(()=>g.observe(high,60000)).toThrow('CPU')
  g.observe(sample(),60000);g.observe(high,61000);g.observe(high,120999)
})
