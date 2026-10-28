package com.studyagent.infra.mq;

import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.common.verla.util.VerlaRoutingKey;
import com.studyagent.common.verla.util.VerlaShardCalculator;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
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
 *   verla.cmd.materials   ← cmd.materials.generate   (V2)
 *   verla.cmd.attachment  ← cmd.attachment.parse     (V2)
 *   verla.cmd.aitutor     ← cmd.aitutor.chat / cmd.aitutor.chat.control.cancel  (Demo)
 *   verla.cmd.note        ← cmd.note.generate        (Notes)
 *   verla.cmd.videotutor  ← cmd.video_tutor.chat / cmd.video_tutor.chat.control.cancel
 *                         （唯一<strong>不带</strong> x-single-active-consumer 的命令队列，见 buildVerlaParallelQueue）
 *
 * 事件侧（新增 studyagent.events Topic）：
 *   verla.event.s00 ~ s03 ← verla.event.s{shard}.#
 *
 * DLX：studyagent.dlx Topic
 *   verla.dlq             ← #
 *
 * Alternate Exchange：studyagent.unroutable Fanout（events 落不下来时兜底）
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
    /** V2: 学习材料 Flashcard / Quiz 等 */
    public static final String CMD_MATERIALS_QUEUE   = "verla.cmd.materials";
    /** V2: finalize 上传后触发附件解析 */
    public static final String CMD_ATTACHMENT_QUEUE = "verla.cmd.attachment";
    /** Demo: AI Tutor copilot 对话（cmd.aitutor.chat / cmd.aitutor.chat.control.cancel） */
    public static final String CMD_AITUTOR_QUEUE    = "verla.cmd.aitutor";
    /** Notes: 单轮「素材 → 笔记」生成 */
    public static final String CMD_NOTE_QUEUE       = "verla.cmd.note";
    /** Video Tutor: 交互式白板教学 turn */
    public static final String CMD_VIDEO_TUTOR_QUEUE = "verla.cmd.videotutor";

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

    /**
     * 并发消费的 Verla 队列：durable + 可选 DLX，但**不带** {@code x-single-active-consumer}。
     * <p>
     * 为什么必须在这一层做：{@link #singleActiveConsumerEnabled} 是<b>全局</b>开关，
     * 一旦为 true 就所有 Verla 命令队列一起变成"全集群同时只跑一个 turn"；
     * 而 Python 侧那份 per-queue SAC 清单在 {@code passive=true}（默认）下根本不向下发
     * arguments，所以改它没有用 —— 队列参数的唯一真相在这里。
     * <p>
     * Video Tutor 适合并发：一轮白板对话不持有跨轮进程状态（上下文由 Java 每轮下发），
     * 同一会话的串行由前端 composer 锁定 + 每轮新建 session 保证。
     * 队列参数不可变 —— 新建队列怎么声明都安全，但日后要改必须删队列重建（有停机窗口）。
     */
    private Queue buildVerlaParallelQueue(String queueName) {
        QueueBuilder b = QueueBuilder.durable(queueName);
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
    public TopicExchange verlaDlxExchange() {
        return new TopicExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public FanoutExchange verlaUnroutableExchange() {
        return new FanoutExchange(UNROUTABLE_EXCHANGE, true, false);
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
    public Binding verlaCmdPlanTaskNameBinding(Queue verlaCmdPlanQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdPlanQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_PLAN_TASK_NAME.getCode());
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
    public Binding verlaCmdFileChatBinding(Queue verlaCmdAgentQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdAgentQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_FILE_CHAT.getCode());
    }

    @Bean
    public Binding verlaCmdSimulationGenerateBinding(Queue verlaCmdAgentQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdAgentQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_SIMULATION_GENERATE.getCode());
    }

    @Bean
    public Binding verlaCmdControlCancelBinding(Queue verlaCmdControlQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdControlQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_AGENT_CANCEL.getCode());
    }

    @Bean
    public Binding verlaCmdSimulationCancelBinding(Queue verlaCmdControlQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdControlQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_SIMULATION_CONTROL_CANCEL.getCode());
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
    public Binding verlaCmdAttachmentBinding(Queue verlaCmdAttachmentQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdAttachmentQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_ATTACHMENT_PARSE.getCode());
    }

    @Bean
    public Queue verlaCmdMaterialsQueue() {
        return buildVerlaConsumerQueue(CMD_MATERIALS_QUEUE);
    }

    @Bean
    public Binding verlaCmdMaterialsBinding(Queue verlaCmdMaterialsQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdMaterialsQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_MATERIALS_GENERATE.getCode());
    }

    // -------------------- Video Tutor Command Queue --------------------

    /** 不吃 SAC：见 {@link #buildVerlaParallelQueue}。 */
    @Bean
    public Queue verlaCmdVideoTutorQueue() {
        return buildVerlaParallelQueue(CMD_VIDEO_TUTOR_QUEUE);
    }

    @Bean
    public Binding verlaCmdVideoTutorChatBinding(Queue verlaCmdVideoTutorQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdVideoTutorQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_VIDEO_TUTOR_CHAT.getCode());
    }

    @Bean
    public Binding verlaCmdVideoTutorCancelBinding(Queue verlaCmdVideoTutorQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdVideoTutorQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_VIDEO_TUTOR_CHAT_CONTROL_CANCEL.getCode());
    }

    @Bean
    public Binding verlaCmdAssignmentInitBinding(Queue verlaCmdAgentQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdAgentQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_ASSIGNMENT_INIT.getCode());
    }

    @Bean
    public Binding verlaCmdAssignmentDeepUnderstandingBinding(Queue verlaCmdAgentQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdAgentQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_ASSIGNMENT_DEEP_UNDERSTANDING.getCode());
    }

    @Bean
    public Binding verlaCmdAgentDetectionBinding(Queue verlaCmdAgentQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdAgentQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_DETECTION_RUN.getCode());
    }

    @Bean
    public Binding verlaCmdSlidesConvertBinding(Queue verlaCmdAgentQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdAgentQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_SLIDES_CONVERT_TO_EDITOR_JSON.getCode());
    }

    @Bean
    public Binding verlaCmdAgentHumanizerBinding(Queue verlaCmdAgentQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdAgentQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_HUMANIZER_RUN.getCode());
    }

    // -------------------- Demo: AI Tutor Command Queue --------------------

    @Bean
    public Queue verlaCmdAiTutorQueue() {
        return buildVerlaConsumerQueue(CMD_AITUTOR_QUEUE);
    }

    @Bean
    public Binding verlaCmdAiTutorBinding(Queue verlaCmdAiTutorQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdAiTutorQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_AITUTOR_CHAT.getCode());
    }

    @Bean
    public Binding verlaCmdAiTutorCancelBinding(Queue verlaCmdAiTutorQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdAiTutorQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_AITUTOR_CHAT_CONTROL_CANCEL.getCode());
    }

    // -------------------- Notes Command Queue --------------------

    @Bean
    public Queue verlaCmdNoteQueue() {
        return buildVerlaConsumerQueue(CMD_NOTE_QUEUE);
    }

    @Bean
    public Binding verlaCmdNoteGenerateBinding(Queue verlaCmdNoteQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdNoteQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_NOTE_GENERATE.getCode());
    }

    @Bean
    public Binding verlaCmdNoteCancelBinding(Queue verlaCmdNoteQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(verlaCmdNoteQueue).to(commandExchange)
                .with(VerlaCommandAction.CMD_NOTE_GENERATE_CONTROL_CANCEL.getCode());
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
    public Binding verlaDlqBindingAll(Queue verlaDlqQueue, TopicExchange verlaDlxExchange) {
        return BindingBuilder.bind(verlaDlqQueue).to(verlaDlxExchange).with("#");
    }

    @Bean
    public Queue verlaUnroutableQueue() {
        return QueueBuilder.durable(UNROUTABLE_QUEUE).build();
    }

    @Bean
    public Binding verlaUnroutableBinding(Queue verlaUnroutableQueue, FanoutExchange verlaUnroutableExchange) {
        return BindingBuilder.bind(verlaUnroutableQueue).to(verlaUnroutableExchange);
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
