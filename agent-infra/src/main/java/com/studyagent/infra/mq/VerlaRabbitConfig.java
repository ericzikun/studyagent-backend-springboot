package com.studyagent.infra.mq;

import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.common.verla.util.VerlaRoutingKey;
import com.studyagent.common.verla.util.VerlaShardCalculator;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Verla 链路 RabbitMQ 拓扑配置
 * <p>
 * 对应文档 docs/verla-Java侧MVP技术方案.md §5
 * <pre>
 * 命令侧（复用 studyagent.command Direct）：
 *   verla.cmd.plan        ← cmd.plan.intent
 *   verla.cmd.agent       ← cmd.assignment.run
 *   verla.cmd.control     ← cmd.agent.control.cancel / retry
 *   verla.cmd.clarify     ← cmd.clarify.submit       (V2)
 *   verla.cmd.attachment  ← cmd.attachment.parse     (V2)
 *
 * 事件侧（新增 studyagent.events Topic）：
 *   verla.event.s00 ~ s03 ← verla.event.s{shard}.#
 *
 * DLX：studyagent.dlx Direct
 *   verla.dlq             ← #
 *
 * Alternate Exchange：studyagent.unroutable Direct（events 落不下来时兜底）
 *   verla.unroutable.q
 * </pre>
 */
@Configuration
public class VerlaRabbitConfig {

    // -------------------- Exchanges --------------------

    public static final String COMMAND_EXCHANGE     = RabbitMQConfig.COMMAND_EXCHANGE; // 复用
    public static final String EVENTS_EXCHANGE      = "studyagent.events";
    public static final String DLX_EXCHANGE         = "studyagent.dlx";
    public static final String UNROUTABLE_EXCHANGE  = "studyagent.unroutable";

    // -------------------- Verla 命令队列 --------------------

    public static final String CMD_PLAN_QUEUE       = "verla.cmd.plan";
    public static final String CMD_AGENT_QUEUE      = "verla.cmd.agent";
    public static final String CMD_CONTROL_QUEUE    = "verla.cmd.control";
    /** V2: 用户提交澄清问卷响应 */
    public static final String CMD_CLARIFY_QUEUE    = "verla.cmd.clarify";
    /** V2: finalize 上传后触发附件解析 */
    public static final String CMD_ATTACHMENT_QUEUE = "verla.cmd.attachment";

    // -------------------- DLX / 兜底 --------------------

    public static final String DLQ_QUEUE         = "verla.dlq";
    public static final String UNROUTABLE_QUEUE  = "verla.unroutable.q";

    /**
     * 由 application.yml 注入；默认值 {@link VerlaShardCalculator#DEFAULT_SHARD_COUNT}。
     */
    @Value("${verla.mq.shard-count:" + VerlaShardCalculator.DEFAULT_SHARD_COUNT + "}")
    private int shardCount;

    /**
     * 是否为 Verla 命令队列 / 事件 shard 队列声明 {@code x-dead-letter-exchange}。
     * <p>
     * 若 Broker 上已有同名队列且<strong>未</strong>配置死信交换机，而此处设为 {@code true}，
     * RabbitMQ 会返回 {@code PRECONDITION_FAILED inequivalent arg 'x-dead-letter-exchange'}，
     * 导致 Spring 启动失败。本地沿用旧拓扑时可设为 {@code false}；生产建议 {@code true}，
     * 或删除旧队列后由应用按新参数重建。
     */
    @Value("${verla.mq.dead-letter-enabled:true}")
    private boolean deadLetterEnabled;

    /**
     * 是否为 Verla 消费队列声明 {@code x-single-active-consumer=true}。
     * <p>
     * 旧拓扑队列若未带该参数，声明时会 {@code PRECONDITION_FAILED inequivalent arg 'x-single-active-consumer'}。
     * 本地兼容时可设为 {@code false}；生产多实例抢同一队列时建议 {@code true}，或删队列后重建。
     */
    @Value("${verla.mq.single-active-consumer-enabled:true}")
    private boolean singleActiveConsumerEnabled;

    public int getShardCount() {
        return shardCount;
    }

    public void setShardCount(int shardCount) {
        this.shardCount = shardCount;
    }

    /** Verla 消费队列： durable + 可选 single-active-consumer + 可选 DLX */
    private Queue buildVerlaConsumerQueue(String queueName) {
        QueueBuilder b = QueueBuilder.durable(queueName);
        if (singleActiveConsumerEnabled) {
            b = b.withArgument("x-single-active-consumer", true);
        }
        if (deadLetterEnabled) {
            b = b.withArgument("x-dead-letter-exchange", DLX_EXCHANGE);
        }
        return b.build();
    }

    // ===================== Exchanges =====================

    @Bean
    public TopicExchange verlaEventsExchange() {
        // 设置 alternate exchange，未路由的事件兜到 unroutable
        TopicExchange ex = new TopicExchange(EVENTS_EXCHANGE, true, false,
                java.util.Map.of("alternate-exchange", UNROUTABLE_EXCHANGE));
        return ex;
    }

    @Bean
    public DirectExchange verlaDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange verlaUnroutableExchange() {
        return new DirectExchange(UNROUTABLE_EXCHANGE, true, false);
    }

    // ===================== Command Queues =====================

    @Bean
    public Queue verlaCmdPlanQueue() {
        return buildVerlaConsumerQueue(CMD_PLAN_QUEUE);
    }

    @Bean
    public Queue verlaCmdAgentQueue() {
        return buildVerlaConsumerQueue(CMD_AGENT_QUEUE);
    }

    @Bean
    public Queue verlaCmdControlQueue() {
        return buildVerlaConsumerQueue(CMD_CONTROL_QUEUE);
    }

    @Bean
    public Binding verlaCmdPlanBinding(Queue verlaCmdPlanQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdPlanQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_PLAN_INTENT.getCode());
    }

    @Bean
    public Binding verlaCmdAgentBinding(Queue verlaCmdAgentQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdAgentQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_ASSIGNMENT_RUN.getCode());
    }

    @Bean
    public Binding verlaCmdAssignmentClarifyBinding(Queue verlaCmdAgentQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdAgentQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_ASSIGNMENT_CLARIFY.getCode());
    }

    @Bean
    public Binding verlaCmdControlCancelBinding(Queue verlaCmdControlQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdControlQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_AGENT_CANCEL.getCode());
    }

    @Bean
    public Binding verlaCmdControlRetryBinding(Queue verlaCmdControlQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdControlQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_AGENT_RETRY.getCode());
    }

    // -------------------- V2 Command Queues --------------------

    @Bean
    public Queue verlaCmdClarifyQueue() {
        return buildVerlaConsumerQueue(CMD_CLARIFY_QUEUE);
    }

    @Bean
    public Binding verlaCmdClarifyBinding(Queue verlaCmdClarifyQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdClarifyQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_CLARIFY_SUBMIT.getCode());
    }

    @Bean
    public Queue verlaCmdAttachmentQueue() {
        return buildVerlaConsumerQueue(CMD_ATTACHMENT_QUEUE);
    }

    @Bean
    public Binding verlaCmdAgentDetectionBinding(Queue verlaCmdAgentQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdAgentQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_DETECTION_RUN.getCode());
    }

    @Bean
    public Binding verlaCmdAgentHumanizerBinding(Queue verlaCmdAgentQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdAgentQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_HUMANIZER_RUN.getCode());
    }

    // ===================== Event Shard Queues =====================

    /**
     * 注册 shardCount 个 event 队列（`verla.event.s00` .. `verla.event.s{N-1}`），
     * 每队列绑 `verla.event.s{NN}.#` 到 events Topic Exchange。
     * 同时全部绑到 DLX，处理失败的消息进 verla.dlq。
     */
    @Bean
    public List<ShardDeclarable> verlaEventShardDeclarables() {
        List<ShardDeclarable> all = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            String qName = VerlaRoutingKey.queueOfShard(i);
            String pattern = VerlaRoutingKey.bindingPatternOfShard(i);
            Queue q = buildVerlaConsumerQueue(qName);
            Binding b = BindingBuilder.bind(q).to(verlaEventsExchange()).with(pattern);
            all.add(new ShardDeclarable(qName, q, b));
        }
        return all;
    }

    /**
     * 将上面动态构造的 Queue / Binding 暴露成独立 bean，便于 RabbitAdmin 自动声明。
     * 通过 @PostConstruct 在 BeanFactory 注册。
     */
    @Bean
    public RabbitDeclarableRegistrar verlaShardRegistrar(
            org.springframework.beans.factory.config.ConfigurableBeanFactory beanFactory) {
        return new RabbitDeclarableRegistrar(beanFactory, verlaEventShardDeclarables());
    }

    // ===================== DLQ / Unroutable =====================

    @Bean
    public Queue verlaDlqQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding verlaDlqBindingAll(Queue verlaDlqQueue, DirectExchange verlaDlxExchange) {
        // DirectExchange 用空 routing key + 通配符替代不支持，这里用 # 也行不通；
        // MVP 简化：消费者 nack-no-requeue 后 RabbitMQ 自动按原 routing key 投递到 DLX，
        // 因此 dlq 用空 routing key（与 publish 时 originalRoutingKey 等价）做兜底匹配。
        return BindingBuilder.bind(verlaDlqQueue).to(verlaDlxExchange).with("");
    }

    @Bean
    public Queue verlaUnroutableQueue() {
        return QueueBuilder.durable(UNROUTABLE_QUEUE).build();
    }

    @Bean
    public Binding verlaUnroutableBinding(Queue verlaUnroutableQueue, DirectExchange verlaUnroutableExchange) {
        return BindingBuilder.bind(verlaUnroutableQueue).to(verlaUnroutableExchange).with("");
    }

    // ===================== ListenerContainerFactory =====================

    /**
     * Verla 专用 listener 工厂：
     * <ul>
     *     <li>{@code prefetch=1} —— 同 shard 严格串行</li>
     *     <li>{@code concurrency=1-1} —— 单线程消费</li>
     *     <li>{@code manual ack} —— 异常时手动 nack</li>
     * </ul>
     */
    @Bean(name = "verlaListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory verlaListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
        f.setConnectionFactory(connectionFactory);
        f.setMessageConverter(jsonMessageConverter);
        f.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        f.setPrefetchCount(1);
        f.setConcurrentConsumers(1);
        f.setMaxConcurrentConsumers(1);
        f.setMissingQueuesFatal(false);
        return f;
    }

    // ===================== Internal Helpers =====================

    /**
     * 一组动态生成的 (Queue, Binding) 待声明对，便于 {@link RabbitDeclarableRegistrar} 注册。
     * 名字避开 Spring AMQP 自己的 {@code org.springframework.amqp.core.Declarable} 接口。
     */
    public record ShardDeclarable(String name, Queue queue, Binding binding) {}
}
