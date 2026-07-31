package com.yanban.sandboxbroker;
import com.yanban.sandbox.contract.SandboxDispatch;
import com.yanban.sandbox.contract.SandboxDispatchResponse;
import com.yanban.sandbox.contract.SandboxExecutionView;
import java.nio.charset.StandardCharsets; import java.security.MessageDigest; import java.util.Map;
import org.springframework.http.HttpStatus; import org.springframework.web.bind.annotation.*; import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
@RestController @RequestMapping("/internal/v1") @ConditionalOnProperty(prefix="yanban.broker",name="enabled",havingValue="true")
final class BrokerController {
 private static final Logger log=LoggerFactory.getLogger(BrokerController.class);
 private static final long PROVIDER_HEALTH_TIMEOUT_MILLIS=15000;
 private final BrokerProperties properties; private final SandboxDispatchService executions; private final ProviderEnvironment providerEnvironment; private final SandboxProviderCommandFactory providers;
 BrokerController(BrokerProperties p,SandboxDispatchService e,ProviderEnvironment providerEnvironment,SandboxProviderCommandFactory providers){properties=p;executions=e;this.providerEnvironment=providerEnvironment;this.providers=providers;}
 @GetMapping("/health") Map<String,String> health(@RequestHeader(value="Authorization",required=false) String auth){authenticate(auth);SandboxProviderCommands commands=providers.commands();providerHealth(commands);return Map.of("status","UP","provider",commands.provider());}
 @PostMapping("/executions") SandboxDispatchResponse execute(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody SandboxDispatch request){authenticate(auth);return executions.dispatch(request);}
 @GetMapping("/executions/{id}") SandboxExecutionView status(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id){authenticate(auth);return executions.status(id);}
 @PostMapping("/executions/{id}/cancel") void cancel(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String id,@RequestParam long fence){authenticate(auth);executions.cancel(id,fence);}
 private void authenticate(String header){byte[] expected=("Bearer "+properties.getBearerToken()).getBytes(StandardCharsets.UTF_8);byte[] actual=(header==null?"":header).getBytes(StandardCharsets.UTF_8);if(!MessageDigest.isEqual(expected,actual))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);}
 static long providerHealthTimeoutMillis(){return PROVIDER_HEALTH_TIMEOUT_MILLIS;}
 private void providerHealth(SandboxProviderCommands commands){
  Process process=null;
  try{
   ProcessBuilder builder=new ProcessBuilder(commands.health());providerEnvironment.apply(builder);builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);process=builder.start();
   if(!process.waitFor(PROVIDER_HEALTH_TIMEOUT_MILLIS,java.util.concurrent.TimeUnit.MILLISECONDS)){
    process.destroyForcibly();
    log.warn("Sandbox provider health failed provider={} outcome=TIMEOUT timeoutMillis={}",commands.provider(),PROVIDER_HEALTH_TIMEOUT_MILLIS);
    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"provider unavailable");
   }
   int exitCode=process.exitValue();
   if(exitCode!=0){
    byte[] diagnosticBytes=process.getErrorStream().readNBytes(4097);
    String diagnosticCode=diagnosticBytes.length>4096
      ?"OUTPUT_LIMIT"
      :SandboxWorker.providerErrorType(new String(diagnosticBytes,java.nio.charset.StandardCharsets.UTF_8));
    log.warn("Sandbox provider health failed provider={} outcome=EXIT_NON_ZERO exitCode={} providerErrorType={}",commands.provider(),exitCode,diagnosticCode);
    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"provider unavailable");
   }
  }catch(ResponseStatusException ex){throw ex;}catch(Exception ex){
   if(process!=null)process.destroyForcibly();
   log.warn("Sandbox provider health failed provider={} outcome=START_EXCEPTION exceptionType={}",commands.provider(),ex.getClass().getSimpleName());
   throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"provider unavailable");
  }
 }
}
