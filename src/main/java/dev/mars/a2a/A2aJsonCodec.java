package dev.mars.a2a;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import org.a2aproject.sdk.grpc.SendMessageRequest;
import org.a2aproject.sdk.grpc.SendMessageResponse;
import org.a2aproject.sdk.grpc.ListTasksResponse;
import org.a2aproject.sdk.grpc.StreamResponse;
import org.a2aproject.sdk.grpc.mapper.AgentCardMapper;
import org.a2aproject.sdk.grpc.mapper.MessageMapper;
import org.a2aproject.sdk.grpc.mapper.MessageSendParamsMapper;
import org.a2aproject.sdk.grpc.mapper.TaskMapper;
import org.a2aproject.sdk.grpc.mapper.TaskStatusUpdateEventMapper;
import org.a2aproject.sdk.grpc.mapper.TaskArtifactUpdateEventMapper;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;

/** ProtoJSON codec backed by the normative A2A v1.0 generated schema. */
final class A2aJsonCodec {

  private static final JsonFormat.Parser PARSER = JsonFormat.parser();
  private static final JsonFormat.Printer PRINTER = JsonFormat.printer()
      .omittingInsignificantWhitespace();

  String encodeAgentCard(AgentCard card) {
    return print(AgentCardMapper.INSTANCE.toProto(card));
  }

  MessageSendParams decodeSendMessage(String json) {
    SendMessageRequest.Builder request = SendMessageRequest.newBuilder();
    try {
      PARSER.merge(json, request);
      return MessageSendParamsMapper.INSTANCE.fromProto(request.build());
    } catch (InvalidProtocolBufferException error) {
      throw new IllegalArgumentException("Invalid A2A SendMessage request", error);
    }
  }

  String encodeSendMessageResponse(EventKind event) {
    SendMessageResponse.Builder response = SendMessageResponse.newBuilder();
    if (event instanceof Message message) {
      response.setMessage(MessageMapper.INSTANCE.toProto(message));
    } else if (event instanceof Task task) {
      response.setTask(TaskMapper.INSTANCE.toProto(task));
    } else {
      throw new IllegalArgumentException(
          "SendMessage must return a Message or Task, got " + event.getClass().getName());
    }
    return print(response);
  }

  String encodeTask(Task task) {
    return print(TaskMapper.INSTANCE.toProto(task));
  }

  String encodeTaskPage(A2aTaskPage page) {
    ListTasksResponse.Builder response = ListTasksResponse.newBuilder()
        .setTotalSize(page.totalSize())
        .setPageSize(page.pageSize())
        .setNextPageToken(page.nextPageToken());
    page.tasks().forEach(task -> response.addTasks(TaskMapper.INSTANCE.toProto(task)));
    try {
      var nextToken = ListTasksResponse.getDescriptor()
          .findFieldByName("next_page_token");
      return PRINTER.includingDefaultValueFields(java.util.Set.of(nextToken))
          .print(response);
    } catch (InvalidProtocolBufferException error) {
      throw new IllegalStateException("Unable to encode A2A task page", error);
    }
  }

  String encodeStreamResponse(StreamingEventKind event) {
    StreamResponse.Builder response = StreamResponse.newBuilder();
    if (event instanceof Message message) {
      response.setMessage(MessageMapper.INSTANCE.toProto(message));
    } else if (event instanceof Task task) {
      response.setTask(TaskMapper.INSTANCE.toProto(task));
    } else if (event instanceof TaskStatusUpdateEvent status) {
      response.setStatusUpdate(TaskStatusUpdateEventMapper.INSTANCE.toProto(status));
    } else if (event instanceof TaskArtifactUpdateEvent artifact) {
      response.setArtifactUpdate(TaskArtifactUpdateEventMapper.INSTANCE.toProto(artifact));
    } else {
      throw new IllegalArgumentException(
          "Unsupported A2A streaming event " + event.getClass().getName());
    }
    return print(response);
  }

  private String print(com.google.protobuf.MessageOrBuilder value) {
    try {
      return PRINTER.print(value);
    } catch (InvalidProtocolBufferException error) {
      throw new IllegalStateException("Unable to encode A2A protocol message", error);
    }
  }
}
