import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { cpus, platform, release } from 'node:os'
import { distribution, until } from './core.ts'
import { approvedIdentity, privateSessions, remoteConfig } from './remote-policy.ts'
import { observeHosts, remoteCommand } from './remote-io.ts'
import { configureRemoteTarget, json } from './transport.ts'
import { collaboration } from './collaboration.ts'
import { execution } from './execution.ts'

const [target,pwsh,destination] = process.argv.slice(2)
if (!target || !pwsh || !destination || process.argv.length !== 5) throw new Error('Explicit HTTPS target, PowerShell executable and report directory required')
configureRemoteTarget(target)
const settings = remoteConfig(JSON.parse(await readFile(new URL('./local.json',import.meta.url),'utf8')))
const directory = resolve(destination)
await mkdir(directory,{recursive:true})
const abort = new AbortController()
const inputTimer = setTimeout(()=>{ console.error('Private authentication input deadline exceeded'); process.exit(1) },60000)
let input=''
for await (const chunk of process.stdin) { input+=String(chunk); if(input.length>32768) throw new Error('Private input bound exceeded') }
clearTimeout(inputTimer)
const sessions = privateSessions(input,Date.now()); input=''
const timeout = setTimeout(()=>abort.abort(),Math.min(900000,...sessions.map(s=>Date.parse(s.expiresAt)-Date.now()-5000)))
const interrupt=()=>abort.abort();process.once('SIGINT',interrupt);process.once('SIGTERM',interrupt)
const report: Record<string,unknown> = { started:new Date().toISOString(), settings, target, environment:{kind:'AWS two-host deployment',generatorOS:platform(),generatorRelease:release(),generatorCPU:cpus()[0]?.model,node:process.version},collaboration:[],execution:[],passed:false }
const collab=report.collaboration as unknown[], executions=report.execution as unknown[]
let observer: ReturnType<typeof observeHosts> | undefined, phase='authentication'
const save=()=>writeFile(resolve(directory,'report.json'),JSON.stringify(report,null,2)+'\n')
try {
  const tokens=sessions.map(s=>s.accessToken)
  const identities=await Promise.all(tokens.map(t=>json<{id:string}>('/auth/me',t,undefined,abort.signal,10000)))
  approvedIdentity(identities.map(i=>i.id));report.approvedIdentitiesVerified=true
  observer=observeHosts(pwsh,abort)
  await until(()=>{try {observer!.guard.fresh(performance.now());return true}catch{return false}},60000,abort.signal)
  observer.startGuard()
  const room=await json<{room:{id:string};invitationToken:string}>('/rooms',tokens[0],{name:'Milestone 16 AWS bounded benchmark',language:'PYTHON'},abort.signal,10000)
  // Private correlation file contains only the new room UUID, never tokens.
  await writeFile(resolve(directory,'room-id.txt'),room.room.id)
  await json(`/rooms/${room.room.id}/join`,tokens[1],{invitationToken:room.invitationToken},abort.signal,10000)
  for(let repetition=1;repetition<=3;repetition++) for(const connections of settings.stages) {
    phase=`collaboration-${connections}-repeat-${repetition}`;observer.phase(phase);console.log(phase)
    const result=await collaboration(settings,room.room.id,tokens,connections,abort.signal)
    collab.push({repetition,...result});await save()
    if(!result.passed)throw new Error('Collaboration observation failed')
    await delay(500,undefined,{signal:abort.signal})
  }
  let last=-Infinity,total=0
  for(let repetition=1;repetition<=3;repetition++) for(const language of ['JAVA','PYTHON'] as const) {
    const pause=60000-(performance.now()-last)
    if(pause>0){observer.phase('execution-cooldown');await delay(pause,undefined,{signal:abort.signal})}
    abort.signal.throwIfAborted();observer.guard.fresh(performance.now())
    if(total+5>30)throw new Error('Submission bound reached')
    phase=`execution-${language}-repeat-${repetition}`;observer.phase(phase);console.log(phase);last=performance.now()
    const result=await execution(settings,room.room.id,tokens,language,abort.signal);total+=result.submitted
    const safeJobs=result.jobs.map(j=>({warmup:j.warmup,status:j.status,acceptanceMs:j.acceptanceMs,endToEndMs:j.endToEndMs}))
    executions.push({repetition,...result,jobs:safeJobs})
    if(!result.passed)throw new Error('Execution failed; no uncertain submission retried')
    await save()
    const rows=await remoteCommand(pwsh,'App','timings',room.room.id) as {id:string;status:string;queue_ms:number;duration_ms:number}[]
    if(!Array.isArray(rows)||rows.length>30)throw new Error('Durable timing bound failed')
    const jobs=result.jobs.map((j,i)=>{
      const row=rows.find(r=>r.id===j.id)
      if(!row || row.status!==j.status || !Number.isFinite(row.queue_ms)||row.queue_ms<0 || !Number.isFinite(row.duration_ms)||row.duration_ms<0)throw new Error('Missing durable timing')
      return {...safeJobs[i],queueMs:row.queue_ms,durationMs:row.duration_ms}
    })
    const measured=jobs.filter(j=>!j.warmup)
    executions[executions.length-1]={repetition,...result,jobs,queueWaitMs:distribution(measured.map(j=>j.queueMs)),durationMs:distribution(measured.map(j=>j.durationMs))};await save()
  }
  phase='final health and drain';observer.phase(phase)
  await delay(8000,undefined,{signal:abort.signal});observer.guard.fresh(performance.now())
  const appSamples=observer.samples as {role:string;ready:number;unacked:number}[]
  const app=appSamples.filter(s=>s.role==='App').at(-1)
  if(!app || app.ready || app.unacked)throw new Error('Execution queues did not drain')
  report.cleanup=await remoteCommand(pwsh,'Worker','cleanup')
  abort.signal.throwIfAborted()
  report.passed=true
} catch {
  report.failure={phase,reason:abort.signal.aborted?'Deadline, interruption or capacity guard stopped work':'Scenario or inspection failed; no uncertain submission retried'};process.exitCode=1
} finally {
  clearTimeout(timeout)
  if(observer){await observer.stop();report.resources=observer.samples;report.resourceErrors=observer.failures;if(observer.failures.length){report.passed=false;process.exitCode=1}}
  sessions.forEach(s=>{s.accessToken=''})
  report.finished=new Date().toISOString();await save()
  process.removeListener('SIGINT',interrupt);process.removeListener('SIGTERM',interrupt)
  console.log(`Remote report saved; passed=${report.passed}. Operator backup and host shutdown still required.`)
}
