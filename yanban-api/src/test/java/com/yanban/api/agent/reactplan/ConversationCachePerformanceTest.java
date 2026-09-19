package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentMessageCacheService;
import com.yanban.api.agent.AgentService;
import com.yanban.api.agent.cache.ConversationSnapshotCache;
import com.yanban.core.agent.*;
import jakarta.persistence.EntityManagerFactory;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in benchmark against disposable local containers; never point this at an application database. */
@EnabledIfSystemProperty(named="conversation.perf.enabled", matches="true")
@DataJpaTest(showSql=false, properties={
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13391/conversation_perf?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC",
        "spring.datasource.username=root", "spring.datasource.password=isolated_benchmark_only",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF", "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("conversation-cache-benchmark")
@ContextConfiguration(classes=ConversationCachePerformanceTest.Config.class)
@Import({ConversationSnapshotCache.class, AgentMessageCacheService.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class ConversationCachePerformanceTest {
    @Configuration(proxyBeanMethods=false)
    @Profile("conversation-cache-benchmark")
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses={AgentMessageRepository.class,ReactPlanTurnIntakeRepository.class},
            includeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,classes={
                    AgentSessionRepository.class,AgentMessageRepository.class,ReactPlanTurnIntakeRepository.class,
                    ReactPlanTaskCheckpointRepository.class,ReactPlanTaskEventRepository.class}))
    static class Config {
        @Bean PersistenceManagedTypes managedTypes() {
            return PersistenceManagedTypes.of(AgentSession.class.getName(),AgentMessage.class.getName(),
                    ReactPlanTurnIntakeEntity.class.getName(),ReactPlanTaskCheckpointEntity.class.getName(),
                    ReactPlanTaskEventEntity.class.getName());
        }
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean LettuceConnectionFactory connection() {
            return new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",16391));
        }
        @Bean StringRedisTemplate redis(LettuceConnectionFactory connection) { return new StringRedisTemplate(connection); }
    }
    @Autowired AgentSessionRepository sessions;
    @Autowired AgentMessageRepository messages;
    @Autowired ReactPlanTurnIntakeRepository intakes;
    @Autowired ReactPlanTaskCheckpointRepository checkpoints;
    @Autowired ReactPlanTaskEventRepository events;
    @Autowired ConversationSnapshotCache snapshots;
    @Autowired AgentMessageCacheService messageCache;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManagerFactory emf;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    private static final long USER=9_902_300_000L + Math.floorMod(System.nanoTime(), 1_000_000_000L);

    @Test void compareDatabaseColdAndWarmCacheWithIdenticalResults() throws Exception {
        assertThat(jdbc.queryForObject("select database()",String.class)).isEqualTo("conversation_perf");
        try (var connection=redis.getConnectionFactory().getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
        // Match the existing production message indexes, rather than benchmarking an unindexed table.
        jdbc.execute("create index perf_messages_session_created on agent_messages(session_id,created_at)");
        jdbc.execute("create index perf_messages_user_created on agent_messages(user_id,created_at)");
        enabled(false);
        AgentService chat=mock(AgentService.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(chat,"sessions",sessions);
        ReflectionTestUtils.setField(chat,"messages",messages);
        ReflectionTestUtils.setField(chat,"messageCache",messageCache);
        var tx=new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        for (int size : new int[]{500,4000}) {
            long session=seedMessages(size);
            benchmark("workspace_50x"+size+"chars",session,
                    () -> tx.execute(status -> chat.listMessages(USER,session,50,null,"all")));
        }
        long project=seedProject();
        var projectQueries=new ReactPlanSessionTaskQueryService(json,sessions,messages,intakes,checkpoints,events,snapshots);
        benchmark("project_6tasks_500events_each",project,
                () -> tx.execute(status -> projectQueries.list(USER,project,true,null,12)));
    }

    private long seedMessages(int size) {
        return new TransactionTemplate(transactions).execute(status -> {
            long id=sessions.saveAndFlush(new AgentSession(USER,"benchmark","mock","mock",24,false)).getId();
            List<AgentMessage> values=new ArrayList<>();
            for(int i=0;i<500;i++) values.add(new AgentMessage(id,USER,i%2==0?"user":"assistant",
                    "message "+i+" "+"a".repeat(size),null,null));
            messages.saveAllAndFlush(values);
            return id;
        });
    }

    private long seedProject() {
        return new TransactionTemplate(transactions).execute(status -> {
            long session=sessions.saveAndFlush(new AgentSession(USER,"project benchmark","mock","mock",24,true,
                    AgentSessionScope.PROJECT,1L)).getId();
            LocalDateTime now=LocalDateTime.of(2026,9,19,12,0);
            for(int t=1;t<=6;t++) {
                String task="task."+String.format("%064d",t);
                var message=messages.saveAndFlush(new AgentMessage(session,USER,"user","inspect file "+t,null,null));
                intakes.saveAndFlush(new ReactPlanTurnIntakeEntity(USER,session,"request."+t,"a".repeat(64),t,
                        message.getId(),task,now));
                checkpoints.saveAndFlush(new ReactPlanTaskCheckpointEntity(task,"a".repeat(64),USER,session,t,"succeeded",500,
                        "{\"view\":{\"state\":\"succeeded\",\"lastSequence\":500}}",now));
                List<Object[]> rows=new ArrayList<>();
                for(int e=1;e<=500;e++) rows.add(new Object[]{task,e,
                        "{\"type\":\"message\",\"taskId\":\""+task+"\",\"sequence\":"+e+",\"text\":\""+"b".repeat(500)+"\"}",now});
                jdbc.batchUpdate("insert into reactplan_task_events(task_id,sequence_number,event_json,occurred_at) values (?,?,?,?)",rows);
            }
            return session;
        });
    }

    private void benchmark(String name,long session,Supplier<Object> read) throws Exception {
        enabled(false);
        byte[] expected=encode(read.get());
        // Warm JIT, connection pools and MySQL buffers in both modes before measuring.
        for(int i=0;i<15;i++) { enabled(i%2==0); encode(read.get()); }
        List<Sample> off=new ArrayList<>(),warm=new ArrayList<>(),cold=new ArrayList<>();
        for(int i=0;i<60;i++) {
            // Alternate order to reduce one-sided warmup or load bias.
            boolean on=i%2==0;
            enabled(on); (on?warm:off).add(sample(read,expected));
            enabled(!on); (!on?warm:off).add(sample(read,expected));
        }
        for(int i=0;i<20;i++) {
            enabled(true);
            if(name.startsWith("workspace")) snapshots.invalidate(USER,session);
            else {
                // Only fixture-owned event keys in the disposable Redis; never FLUSHDB.
                var keys=redis.keys("paperagent:conversation:v1:user:"+USER+":session:"+session+":events:*");
                if(keys!=null&&!keys.isEmpty()) redis.delete(keys);
            }
            cold.add(sample(read,expected));
        }
        System.out.println("CACHE_PERF "+json.writeValueAsString(Map.of("scenario",name,"responseBytes",expected.length,
                "disabled",summary(off),"warm",summary(warm),"cold",summary(cold))));
        assertThat(warm.stream().mapToLong(Sample::sql).max().orElseThrow())
                .isLessThan(off.stream().mapToLong(Sample::sql).min().orElseThrow());
    }

    private Sample sample(Supplier<Object> read,byte[] expected) throws Exception {
        var statistics=emf.unwrap(SessionFactory.class).getStatistics();
        long before=statistics.getPrepareStatementCount();
        long start=System.nanoTime();
        byte[] result=encode(read.get());
        double millis=(System.nanoTime()-start)/1_000_000.0;
        long queries=statistics.getPrepareStatementCount()-before;
        assertThat(result).isEqualTo(expected);
        return new Sample(millis,queries);
    }
    private byte[] encode(Object value) throws Exception { return json.writeValueAsBytes(value); }
    private void enabled(boolean value) { ReflectionTestUtils.setField(snapshots,"enabled",value); }
    private Map<String,Object> summary(List<Sample> samples) {
        double[] times=samples.stream().mapToDouble(Sample::millis).sorted().toArray();
        return Map.of("samples",samples.size(),"medianMs",times[times.length/2],
                "p95Ms",times[(int)Math.ceil(times.length*.95)-1],"sqlPerRead",
                samples.stream().mapToLong(Sample::sql).average().orElseThrow());
    }
    private record Sample(double millis,long sql) {}
}
