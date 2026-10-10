package com.studyagent.infra.mq;

import com.studyagent.common.verla.enums.VerlaCommandAction;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Video Tutor 命令队列的拓扑断言。
 * <p>
 * 值得钉住的只有一件事：**videotutor 不吃 x-single-active-consumer**，而 {@code singleActiveConsumerEnabled}
 * 是全局开关。这个开关为 true 时其余命令队列全部串行（全集群同时只跑一个 turn），
 * 白板如果不幸跟着一起串行，实时体验直接归零 —— 而这在功能上是"能跑的"，只会表现成很慢。
 */
class VerlaRabbitConfigVideoTutorTest {

    private static final DirectExchange COMMAND_EXCHANGE =
            new DirectExchange(RabbitMQConfig.COMMAND_EXCHANGE);

    private VerlaRabbitConfig configWithSingleActiveConsumer(boolean sac) {
        VerlaRabbitConfig config = new VerlaRabbitConfig();
        ReflectionTestUtils.setField(config, "singleActiveConsumerEnabled", sac);
        ReflectionTestUtils.setField(config, "deadLetterEnabled", true);
        return config;
    }

    @Test
    void queueNameMatchesThePythonBindingString() {
        assertThat(VerlaRabbitConfig.CMD_VIDEO_TUTOR_QUEUE).isEqualTo("verla.cmd.videotutor");
        assertThat(new VerlaRabbitConfig().verlaCmdVideoTutorQueue().getName())
                .isEqualTo(VerlaRabbitConfig.CMD_VIDEO_TUTOR_QUEUE);
    }

    @Test
    void videoTutorStaysParallelEvenWhenTheGlobalSacSwitchIsOn() {
        VerlaRabbitConfig config = configWithSingleActiveConsumer(true);

        // 前提：全局 SAC 开着，既有命令队列确实带上了该参数
        Queue plan = config.verlaCmdPlanQueue();
        assertThat(plan.getArguments()).containsEntry("x-single-active-consumer", true);

        // 结论：白板队列不受它影响
        Queue videoTutor = config.verlaCmdVideoTutorQueue();
        assertThat(videoTutor.getArguments())
                .as("videotutor 若跟着全局 SAC 走，全集群就只能同时跑一轮白板对话")
                .doesNotContainKey("x-single-active-consumer");
        assertThat(videoTutor.isDurable()).isTrue();
    }

    @Test
    void deadLetterArgumentStillApplies() {
        Queue queue = configWithSingleActiveConsumer(true).verlaCmdVideoTutorQueue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", VerlaRabbitConfig.DLX_EXCHANGE);
    }

    @Test
    void exactlyTwoBindingsOnTheCommandExchange() {
        VerlaRabbitConfig config = new VerlaRabbitConfig();
        Queue queue = config.verlaCmdVideoTutorQueue();

        Binding chat = config.verlaCmdVideoTutorChatBinding(queue, COMMAND_EXCHANGE);
        Binding cancel = config.verlaCmdVideoTutorCancelBinding(queue, COMMAND_EXCHANGE);

        assertThat(chat.getRoutingKey())
                .isEqualTo(VerlaCommandAction.CMD_VIDEO_TUTOR_CHAT.getCode())
                .isEqualTo("cmd.video_tutor.chat");
        assertThat(cancel.getRoutingKey())
                .isEqualTo(VerlaCommandAction.CMD_VIDEO_TUTOR_CHAT_CONTROL_CANCEL.getCode())
                .isEqualTo("cmd.video_tutor.chat.control.cancel");

        for (Binding binding : new Binding[]{chat, cancel}) {
            assertThat(binding.getExchange()).isEqualTo(RabbitMQConfig.COMMAND_EXCHANGE);
            assertThat(binding.getDestination()).isEqualTo(VerlaRabbitConfig.CMD_VIDEO_TUTOR_QUEUE);
            assertThat(binding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
            // 命令队列绝不绑到事件侧 exchange：事件走 sNN.# 通配，新增绑定会把整条事件链改窄
            assertThat(binding.getExchange()).isNotEqualTo(VerlaRabbitConfig.EVENTS_EXCHANGE);
        }
    }

    @Test
    void cancelRoutingKeyKeepsTheSuffixPythonShortCircuitsOn() {
        // Python runtime 靠 ".control.cancel" 后缀短路取消命令；改名会让取消命令进入执行分支
        assertThat(VerlaCommandAction.CMD_VIDEO_TUTOR_CHAT_CONTROL_CANCEL.getCode())
                .endsWith(".control.cancel");
    }

    @Test
    void queueArgumentsMapIsNotSilentlyDroppedBySpring() {
        Map<String, Object> args = configWithSingleActiveConsumer(false)
                .verlaCmdVideoTutorQueue().getArguments();
        assertThat(args).containsOnlyKeys("x-dead-letter-exchange");
    }
}
