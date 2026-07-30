package com.yanban.sandboxbroker;

import com.fasterxml.jackson.databind.*;
import com.yanban.sandbox.contract.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(prefix="yanban.broker",name="enabled",havingValue="true")
class SandboxWorker {
    private static final Logger log = LoggerFactory.getLogger(SandboxWorker.class);
    private static final Duration LEASE=Duration.ofSeconds(30);
    private static final long CREATE_TIMEOUT_MILLIS=60_000L;
    private final SandboxLeaseService leases; private final BrokerProperties properties; private final ObjectMapper json;
    private final SandboxProcessRegistry processes; private final ProviderEnvironment providerEnvironment; private final SandboxProviderCommandFactory providers; private final String owner=UUID.randomUUID().toString();
    SandboxWorker(SandboxLeaseService leases,BrokerProperties properties,ObjectMapper json,SandboxProcessRegistry processes,ProviderEnvironment providerEnvironment,SandboxProviderCommandFactory providers){this.leases=leases;this.properties=properties;this.json=json;this.processes=processes;this.providerEnvironment=providerEnvironment;this.providers=providers;}

    @Scheduled(fixedDelayString="${yanban.broker.poll-delay-ms:1000}")
    void poll(){leases.claim(owner,LEASE).ifPresent(lease -> {
        try { runClaim(lease); }
        catch (RuntimeException lost) { recoverUnexpected(lease,lost); }
    });}

    private void runClaim(SandboxLeaseService.Lease lease){
        SandboxExecutionEntity entity=leases.owned(lease);
        SandboxDispatch request=read(entity.requestJson());
        Path root=workspace(lease.executionId(),lease.recovery());
        SandboxProviderCommands commands=providers.commands();
        if(lease.recovery()){
            boolean clean=cleanup(lease,commands,entity.sandboxName(),root);
            finishRecovery(lease,entity,request,clean);return;
        }
        Instant startedAt=leases.now(lease); ExecResult result=null; SandboxExecutionStatus desired=SandboxExecutionStatus.FAILED; SandboxErrorCode error=null;
        SandboxProviderDiagnostic diagnostic=null;
        String providerPhase="MATERIALIZE";
        try{
            throwIfCancelled(lease);
            renew(lease);
            materialize(root,request.files());
            renew(lease);
            leases.transition(lease,"MATERIALIZING",checkpoint("MATERIALIZED",entity.sandboxName()));
            throwIfCancelled(lease);
            // Interrupting sbx create can leave the provider holding the workspace before the sandbox is listable.
            // Let the bounded create finish, then honor cancellation before policy or user code can run.
            Optional<List<List<String>>> dependencyCommands=SandboxCommandProfiles.dependencyPreparation(request.argv());
            boolean dependencyNetwork=dependencyCommands.isPresent()&&commands.supportsDependencyNetwork();
            boolean coordinateDependencies=dependencyNetwork
                    &&SandboxCommandProfiles.usesJavaDependencies(request.argv());
            providerPhase="CREATE";
            List<String> createCommand=dependencyNetwork
                    ?(coordinateDependencies
                        ?commands.createWithCoordinateDependencyNetwork(entity.sandboxName(),root,request.cpus(),request.memoryBytes(),request.timeoutMillis())
                        :commands.createWithDependencyNetwork(entity.sandboxName(),root,request.cpus(),request.memoryBytes(),request.timeoutMillis()))
                    :commands.create(entity.sandboxName(),root,request.cpus(),request.memoryBytes(),request.timeoutMillis());
            requireOk(execute(lease,createCommand,CREATE_TIMEOUT_MILLIS,65536,false),"create");
            throwIfCancelled(lease);
            leases.transition(lease,"CREATED",checkpoint("CREATED",entity.sandboxName()));
            ExecResult dependencyResult=null;
            long executionDeadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis());
            if(dependencyNetwork){
                providerPhase="DEPENDENCY_NETWORK_VERIFY";
                ExecResult dependencyPolicy=execute(lease,commands.verifyDependencyNetwork(entity.sandboxName()),30000,262144);
                requireOk(dependencyPolicy,"dependency network verification");
                requireDependencyNetwork(dependencyPolicy.stdout());
                providerPhase="DEPENDENCY_INSTALL";
                leases.transition(lease,"RUNNING",checkpoint(providerPhase,entity.sandboxName()));
                long dependencyBudget=Math.min(300000L,Math.max(1L,request.timeoutMillis()/3L));
                long dependencyDeadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(dependencyBudget);
                for(List<String> dependencyCommand:dependencyCommands.orElseThrow()){
                    dependencyResult=execute(lease,commands.exec(entity.sandboxName(),dependencyCommand),
                            remainingMillis(dependencyDeadline,dependencyBudget),request.maxOutputBytes());
                    if(dependencyResult.exitCode()!=0)break;
                }
            }
            providerPhase="NETWORK_POLICY_APPLY";
            requireOk(execute(lease,commands.denyAllNetwork(entity.sandboxName()),30000,65536),"network policy");
            providerPhase="NETWORK_POLICY_VERIFY";
            ExecResult policy=execute(lease,commands.verifyNetworkPolicy(entity.sandboxName()),30000,262144);
            requireOk(policy,"policy verification"); requireDenyAll(policy.stdout());
            if(dependencyNetwork&&dependencyResult!=null&&dependencyResult.exitCode()==0){
                providerPhase="FULL_WORKSPACE_UPLOAD";
                requireOk(execute(lease,commands.syncWorkspace(entity.sandboxName(),root),
                        CREATE_TIMEOUT_MILLIS,65536),"full workspace upload");
            }
            leases.transition(lease,"POLICY_APPLIED",checkpoint("POLICY_APPLIED",entity.sandboxName()));
            leases.transition(lease,"RUNNING",checkpoint("RUNNING",entity.sandboxName()));
            if(dependencyResult!=null&&dependencyResult.exitCode()!=0){
                providerPhase="DEPENDENCY_INSTALL";
                result=dependencyResult;
            }else{
                providerPhase="EXECUTE";
                result=execute(lease,commands.exec(entity.sandboxName(),request.argv()),
                        remainingMillis(executionDeadline,request.timeoutMillis()),request.maxOutputBytes());
            }
            desired=result.exitCode()==0?SandboxExecutionStatus.SUCCEEDED:SandboxExecutionStatus.FAILED;
            if(result.exitCode()!=0){
                String providerError=providerErrorType(result.stderr());
                if(!"UNCLASSIFIED".equals(providerError)&&!"UNREPORTED".equals(providerError)){
                    error=SandboxErrorCode.PROVIDER_REJECTED;
                    diagnostic=new SandboxProviderDiagnostic(
                            providerPhase,"ProviderCommandException",providerError,result.exitCode());
                }
            }
        }catch(OutputLimitException ex){desired=SandboxExecutionStatus.FAILED;error=SandboxErrorCode.PROVIDER_REJECTED;
            diagnostic=diagnostic(providerPhase,ex,"OUTPUT_LIMIT",null);
        }catch(TimeoutException ex){desired=SandboxExecutionStatus.TIMED_OUT;error=SandboxErrorCode.TIMED_OUT;
            diagnostic=diagnostic(providerPhase,ex,"TIMEOUT",null);
        }catch(CancelledException ex){desired=SandboxExecutionStatus.CANCELLED;error=SandboxErrorCode.CANCELLED;
        }catch(Exception ex){
            desired=SandboxExecutionStatus.FAILED;error=SandboxErrorCode.PROVIDER_REJECTED;
            String providerError=ex instanceof ProviderCommandException provider?provider.providerErrorType():"UNCLASSIFIED";
            Integer providerExit=ex instanceof ProviderCommandException provider?provider.exitCode():null;
            diagnostic=diagnostic(providerPhase,ex,providerError,providerExit);
            log.warn("Sandbox provider operation failed executionId={} provider={} phase={} errorType={} providerErrorType={}",
                    lease.executionId(),commands.provider(),providerPhase,ex.getClass().getSimpleName(),
                    providerError);
        }
        if(desired==SandboxExecutionStatus.SUCCEEDED&&leases.cancellationRequested(lease)){desired=SandboxExecutionStatus.CANCELLED;error=SandboxErrorCode.CANCELLED;}
        SandboxReceipt receipt=receipt(lease.executionId(),request,desired,result,error,diagnostic,startedAt,leases.now(lease));
        String receiptJson=write(receipt); String digest=sha256(receiptJson);
        // Persist the authoritative result before cleanup so a crash or lease recovery cannot lose it.
        renew(lease);
        leases.stageReceipt(lease,digest,receiptJson);
        leases.transition(lease,pendingCleanupStatus(desired),checkpoint("CLEANUP_REQUIRED",entity.sandboxName()));
        if(!cleanup(lease,commands,entity.sandboxName(),root)){leases.terminal(lease,"CLEANUP_FAILED",null,null,"CLEANUP_FAILED");return;}
        if(desired==SandboxExecutionStatus.SUCCEEDED&&!leases.terminalSuccessIfNotCancelled(lease,digest,receiptJson)){
            Instant now=leases.now(lease);SandboxReceipt cancelled=receipt(lease.executionId(),request,SandboxExecutionStatus.CANCELLED,result,SandboxErrorCode.CANCELLED,null,startedAt,now);String encoded=write(cancelled);
            leases.terminal(lease,"CANCELLED",sha256(encoded),encoded,"CANCELLED");
        }else if(desired!=SandboxExecutionStatus.SUCCEEDED)leases.terminal(lease,desired.name(),digest,receiptJson,error==null?null:error.name());
    }

    static long createTimeoutMillis(){return CREATE_TIMEOUT_MILLIS;}

    private void recoverUnexpected(SandboxLeaseService.Lease lease,RuntimeException failure) {
        log.error("Sandbox worker failed unexpectedly executionId={} previousStatus={} errorType={} failureLocation={}",
                lease.executionId(),lease.previousStatus(),failure.getClass().getSimpleName(),failureLocation(failure));
        try {
            SandboxExecutionEntity entity = leases.owned(lease);
            SandboxDispatch request = read(entity.requestJson());
            Path root = workspace(lease.executionId(), true);
            SandboxProviderCommands commands = providers.commands();
            Instant now=leases.now(lease);
            SandboxProviderDiagnostic diagnostic=unexpectedDiagnostic(entity.status(),failure);
            SandboxReceipt receipt=receipt(lease.executionId(),request,SandboxExecutionStatus.FAILED,null,
                    SandboxErrorCode.PROVIDER_REJECTED,diagnostic,now,now);
            String encoded=write(receipt);String digest=sha256(encoded);
            leases.stageReceipt(lease,digest,encoded);
            leases.transition(lease, "FAILED_PENDING_CLEANUP", checkpoint("CLEANUP_REQUIRED", entity.sandboxName()));
            if (cleanup(lease, commands, entity.sandboxName(), root))
                leases.terminal(lease, "FAILED", digest, encoded, "PROVIDER_REJECTED");
            else leases.terminal(lease, "CLEANUP_FAILED", null, null, "CLEANUP_FAILED");
        } catch (RuntimeException recoveryFailure) {
            log.error("Sandbox unexpected-failure recovery could not persist receipt executionId={} errorType={} failureLocation={}",
                    lease.executionId(),recoveryFailure.getClass().getSimpleName(),failureLocation(recoveryFailure));
            // Durable non-terminal state remains claimable after its database-time lease expires.
        }
    }

    private void finishRecovery(SandboxLeaseService.Lease lease,SandboxExecutionEntity entity,SandboxDispatch request,boolean clean){
        if(!clean){leases.terminal(lease,"CLEANUP_FAILED",null,null,"CLEANUP_FAILED");return;}
        if(entity.receiptJson()!=null){SandboxReceipt receipt=readReceipt(entity.receiptJson());leases.terminal(lease,receipt.status().name(),entity.receiptDigest(),entity.receiptJson(),receipt.errorCode()==null?null:receipt.errorCode().name());return;}
        SandboxExecutionStatus status=entity.cancelRequested()?SandboxExecutionStatus.CANCELLED:SandboxExecutionStatus.FAILED;
        SandboxErrorCode error=entity.cancelRequested()?SandboxErrorCode.CANCELLED:SandboxErrorCode.PROVIDER_REJECTED;
        SandboxProviderDiagnostic diagnostic=entity.cancelRequested()?null:
                new SandboxProviderDiagnostic("RECOVERY_"+lease.previousStatus(),"LeaseExpiredWithoutReceipt","UNREPORTED",null);
        Instant now=leases.now(lease);SandboxReceipt receipt=receipt(lease.executionId(),request,status,null,error,diagnostic,now,now);String encoded=write(receipt);
        leases.terminal(lease,status.name(),sha256(encoded),encoded,error.name());
    }
    private String pendingCleanupStatus(SandboxExecutionStatus desired){return switch(desired){
        case SUCCEEDED -> "SUCCEEDED_PENDING_CLEANUP"; case TIMED_OUT -> "TIMED_OUT_PENDING_CLEANUP";
        case CANCELLED -> "CANCEL_REQUESTED"; default -> "FAILED_PENDING_CLEANUP";};}
    private boolean cleanup(SandboxLeaseService.Lease lease,SandboxProviderCommands commands,String name,Path root){
        try{leases.transition(lease,"CLEANING",checkpoint("CLEANING",name));processes.terminate(lease.executionId());}
        catch(Exception ex){return false;}
        for(int attempt=1;attempt<=3;attempt++){
            try{
                execute(lease,commands.stop(name),10000,65536,false);
                execute(lease,commands.remove(name),20000,65536,false);
                ExecResult list=execute(lease,commands.list(),10000,1048576,false);
                if(list.exitCode()==0&&!sandboxExists(list.stdout(),name)&&deleteWorkspace(root))return true;
            }catch(Exception ignored){ }
            if(attempt<3){
                try{Thread.sleep(250L*attempt);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
            }
        }
        return false;
    }
    private ExecResult execute(SandboxLeaseService.Lease lease,List<String> argv,long timeout,long limit)throws Exception{
        return execute(lease,argv,timeout,limit,true);
    }
    private ExecResult execute(SandboxLeaseService.Lease lease,List<String> argv,long timeout,long limit,boolean observeCancellation)throws Exception{
        // Provider commands are separate OS processes. Renew at every phase boundary so
        // several individually short commands still share one continuous execution lease.
        renew(lease);
        ProcessBuilder builder=new ProcessBuilder(argv).directory(Path.of(properties.getWorkspaceRoot()).toFile());providerEnvironment.apply(builder);
        Process process=builder.start();processes.register(lease.executionId(),process,observeCancellation);AtomicLong budget=new AtomicLong(limit);AtomicReference<Throwable> failure=new AtomicReference<>();
        ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();
        Thread out=reader(process.getInputStream(),stdout,budget,failure),err=reader(process.getErrorStream(),stderr,budget,failure);out.start();err.start();
        try{long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);long heartbeat=System.nanoTime();
            while(process.isAlive()){
                if(failure.get()!=null){terminate(process);throw failureException(failure.get());}
                if(System.nanoTime()>=deadline){terminate(process);throw new TimeoutException();}
                if(System.nanoTime()-heartbeat>=TimeUnit.SECONDS.toNanos(10)){leases.heartbeat(lease,LEASE);heartbeat=System.nanoTime();}
                SandboxExecutionEntity state=leases.owned(lease);if(observeCancellation&&state.cancelRequested()){terminate(process);throw new CancelledException();}
                process.waitFor(100,TimeUnit.MILLISECONDS);
            }
            out.join(5000);err.join(5000);
            if(out.isAlive()||err.isAlive()||failure.get()!=null)throw new IOException("sandbox output reader failed");
            if(budget.get()<0){terminate(process);throw new OutputLimitException();}
            SandboxExecutionEntity state=leases.owned(lease);if(observeCancellation&&state.cancelRequested())throw new CancelledException();
            return new ExecResult(process.exitValue(),decode(stdout),decode(stderr));
        }finally{processes.clear(lease.executionId(),process);}
    }
    private Exception failureException(Throwable failure){return failure instanceof OutputLimitException limit?limit:new IOException("sandbox output reader failed",failure);}
    private void renew(SandboxLeaseService.Lease lease){leases.heartbeat(lease,LEASE);}
    private void throwIfCancelled(SandboxLeaseService.Lease lease)throws CancelledException{if(leases.cancellationRequested(lease))throw new CancelledException();}
    private Thread reader(InputStream input,ByteArrayOutputStream output,AtomicLong budget,AtomicReference<Throwable> failure){return new Thread(()->{try(input){byte[] b=new byte[8192];int n;while((n=input.read(b))>=0){long left=budget.addAndGet(-n);if(left<0){failure.compareAndSet(null,new OutputLimitException());return;}output.write(b,0,n);}}catch(Throwable ex){failure.compareAndSet(null,ex);}},"sandbox-output-reader");}
    private String decode(ByteArrayOutputStream bytes)throws CharacterCodingException{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();}
    private long remainingMillis(long deadline,long cap)throws TimeoutException{
        long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
        if(remaining<1)throw new TimeoutException();
        return Math.min(remaining,cap);
    }
    private void requireDenyAll(String value)throws Exception{JsonNode root=json.readTree(value);if(!containsRule(root))throw new IllegalStateException("active scoped deny-all rule missing");}
    private void requireDependencyNetwork(String value)throws Exception{
        JsonNode root=json.readTree(value);
        if(!containsExactRule(root,"allow",Set.of("**")))
            throw new IllegalStateException("dependency network permission missing");
    }
    private boolean containsExactRule(JsonNode node,String decision,Set<String> expected){
        if(node==null)return false;
        if(node.isObject()){
            boolean current=("local".equals(node.path("origin").asText())||"scoped".equals(node.path("origin").asText()))
                    &&"network".equals(node.path("resource_type").asText())
                    &&"active".equals(node.path("status").asText())
                    &&decision.equals(node.path("decision").asText())
                    &&exactResources(node.path("resources"),expected);
            if(current)return true;
            var fields=node.fields();while(fields.hasNext())if(containsExactRule(fields.next().getValue(),decision,expected))return true;
        }else if(node.isArray())for(JsonNode child:node)if(containsExactRule(child,decision,expected))return true;
        return false;
    }
    private boolean exactResources(JsonNode resources,Set<String> expected){
        if(!resources.isArray()||resources.size()!=expected.size())return false;
        Set<String> actual=new HashSet<>();
        for(JsonNode resource:resources)if(resource.isTextual())actual.add(resource.asText());else return false;
        return actual.equals(expected);
    }
    private boolean containsRule(JsonNode node){if(node==null)return false;if(node.isObject()){boolean legacy="local".equals(node.path("source").asText())&&"network".equals(node.path("type").asText())&&node.path("active").asBoolean(false)&&"**".equals(node.path("resource").asText());boolean current=("local".equals(node.path("origin").asText())||"scoped".equals(node.path("origin").asText()))&&"network".equals(node.path("resource_type").asText())&&"active".equals(node.path("status").asText())&&containsResource(node.path("resources"),"**");if("deny".equals(node.path("decision").asText())&&(legacy||current))return true;var fields=node.fields();while(fields.hasNext())if(containsRule(fields.next().getValue()))return true;}else if(node.isArray())for(JsonNode child:node)if(containsRule(child))return true;return false;}
    private boolean containsResource(JsonNode resources,String expected){if(!resources.isArray())return false;for(JsonNode resource:resources)if(resource.isTextual()&&expected.equals(resource.asText()))return true;return false;}
    private boolean sandboxExists(String value,String name)throws Exception{JsonNode root=json.readTree(value);return exactName(root,name);}
    private boolean exactName(JsonNode node,String name){if(node==null)return false;if(node.isObject()){if(name.equals(node.path("name").asText()))return true;var it=node.fields();while(it.hasNext())if(exactName(it.next().getValue(),name))return true;}else if(node.isArray())for(JsonNode child:node)if(exactName(child,name))return true;return false;}
    private void materialize(Path root,Map<String,String> files)throws Exception{Files.createDirectory(root);Path canonical=root.toRealPath(LinkOption.NOFOLLOW_LINKS);for(var file:files.entrySet()){Path relative=Path.of(file.getKey());if(relative.isAbsolute()||!relative.normalize().equals(relative)||java.util.stream.StreamSupport.stream(relative.spliterator(),false).anyMatch(part->"..".equals(part.toString())||".".equals(part.toString())))throw new IOException("unsafe path");Path parent=root;for(Path segment:relative.getParent()==null?List.<Path>of():relative.getParent()){parent=parent.resolve(segment.toString());try{Files.createDirectory(parent);}catch(FileAlreadyExistsException exists){if(!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS))throw new IOException("workspace alias",exists);}if(Files.isSymbolicLink(parent)||!parent.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(canonical))throw new IOException("workspace alias");}Path target=root.resolve(relative);Files.writeString(target,file.getValue(),StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS);}}
    private Path workspace(String id,boolean recovery){try{Path base=Path.of(properties.getWorkspaceRoot()).toRealPath(LinkOption.NOFOLLOW_LINKS);Path result=base.resolve(id).normalize();if(!result.getParent().equals(base)||(!recovery&&Files.exists(result,LinkOption.NOFOLLOW_LINKS)))throw new IllegalStateException("workspace collision");return result;}catch(IOException ex){throw new IllegalStateException("workspace unavailable",ex);}}
    private boolean deleteWorkspace(Path root)throws Exception{if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return true;Path base=Path.of(properties.getWorkspaceRoot()).toRealPath(LinkOption.NOFOLLOW_LINKS);Path real=root.toRealPath(LinkOption.NOFOLLOW_LINKS);if(!real.getParent().equals(base)||Files.isSymbolicLink(real))return false;try(var walk=Files.walk(real)){for(Path path:walk.sorted(Comparator.reverseOrder()).toList()){if(Files.isSymbolicLink(path)||!path.normalize().startsWith(real))return false;Files.delete(path);}}return !Files.exists(real,LinkOption.NOFOLLOW_LINKS);}
    private SandboxDispatch read(String value){try{return json.readValue(value,SandboxDispatch.class);}catch(Exception ex){throw new IllegalStateException("stored sandbox request invalid",ex);}}
    private SandboxReceipt readReceipt(String value){try{return json.readValue(value,SandboxReceipt.class);}catch(Exception ex){throw new IllegalStateException("stored sandbox receipt invalid",ex);}}
    private String checkpoint(String phase,String name){return "{\"phase\":\""+phase+"\",\"sandboxName\":\""+name+"\"}";}
    private SandboxReceipt receipt(String id,SandboxDispatch r,SandboxExecutionStatus status,ExecResult x,SandboxErrorCode error,SandboxProviderDiagnostic diagnostic,Instant started,Instant finished){return new SandboxReceipt(id,r.idempotencyKey(),r.requestDigest(),r.userId(),r.projectId(),r.sessionId(),r.planId(),r.stepId(),r.fence(),r.projectVersion(),r.policyDigest(),providers.commands().provider(),status,x==null?null:x.exitCode(),x==null?"":x.stdout(),x==null?"":x.stderr(),false,Map.of(),started,finished,error,diagnostic);}
    static SandboxProviderDiagnostic diagnostic(String phase,Exception failure,String providerErrorType,Integer providerExitCode){
        return new SandboxProviderDiagnostic(phase,failure.getClass().getSimpleName(),providerErrorType,providerExitCode);
    }
    static SandboxProviderDiagnostic unexpectedDiagnostic(String status,RuntimeException failure){
        String phase=status==null||status.isBlank()?"BROKER":"BROKER_"+status;
        return diagnostic(phase,failure,"UNEXPECTED_RUNTIME",null);
    }
    static String failureLocation(Throwable failure){
        StackTraceElement[] stack=failure==null?null:failure.getStackTrace();
        if(stack==null||stack.length==0)return "UNKNOWN";
        StackTraceElement frame=stack[0];
        return frame.getClassName()+"#"+frame.getMethodName()+":"+Math.max(frame.getLineNumber(),0);
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String sha256(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private void requireOk(ExecResult result,String phase){
        if(result.exitCode()!=0)throw new ProviderCommandException(
                phase,result.exitCode(),providerErrorType(result.stderr()));
    }
    static String providerErrorType(String stderr){
        if(stderr==null||stderr.isBlank())return "UNREPORTED";
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile(
                "(?m)^E2B provider error: ([A-Za-z][A-Za-z0-9_.]{0,127}):").matcher(stderr);
        return matcher.find()?matcher.group(1):"UNCLASSIFIED";
    }
    private void terminate(Process process){process.destroy();try{if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();process.destroyForcibly();}}
    private record ExecResult(int exitCode,String stdout,String stderr){}
    private static final class ProviderCommandException extends IllegalStateException {
        private final int exitCode;
        private final String providerErrorType;
        private ProviderCommandException(String phase,int exitCode,String providerErrorType){
            super(phase+" failed with exit code "+exitCode);
            this.exitCode=exitCode;
            this.providerErrorType=providerErrorType;
        }
        private int exitCode(){return exitCode;}
        private String providerErrorType(){return providerErrorType;}
    }
    private static class OutputLimitException extends Exception{} private static class CancelledException extends Exception{}
}
