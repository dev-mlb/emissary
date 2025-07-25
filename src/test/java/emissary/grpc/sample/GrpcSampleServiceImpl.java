package emissary.grpc.sample;

import emissary.grpc.sample.v1.SampleHealthStatus;
import emissary.grpc.sample.v1.SampleRequest;
import emissary.grpc.sample.v1.SampleResponse;
import emissary.grpc.sample.v1.SampleServiceGrpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;

import java.io.ByteArrayOutputStream;

/**
 * Class for overriding behavior of an arbitrary external service.
 */
public abstract class GrpcSampleServiceImpl extends SampleServiceGrpc.SampleServiceImplBase {
    @Override
    public void callSampleHealthCheck(Empty request, StreamObserver<SampleHealthStatus> responseObserver) {
        SampleHealthStatus status = SampleHealthStatus.newBuilder().setOk(true).build();
        responseObserver.onNext(status);
        responseObserver.onCompleted();
    }

    @Override
    public void callSampleService(SampleRequest request, StreamObserver<SampleResponse> responseObserver) {
        SampleResponse response = SampleResponse.newBuilder()
                .setResult(process(request.getQuery()))
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    public ByteString process(ByteString query) {
        return ByteString.copyFrom(process(query.toByteArray()));
    }

    public abstract byte[] process(byte[] query);

    /**
     * Service that repeats each byte in the input array once. E.g. {@code abc} becomes {@code aabbcc}
     */
    public static class RepeatEachCharServiceImpl extends GrpcSampleServiceImpl {
        private static final int REPEAT_COUNT = 2;

        @Override
        public byte[] process(byte[] query) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            for (byte b : query) {
                for (int i = 0; i < REPEAT_COUNT; i++) {
                    outputStream.write(b);
                }
            }
            return outputStream.toByteArray();
        }
    }

    /**
     * Service that duplicates the entire input byte array once. E.g. {@code abc} becomes {@code abcabc}
     */
    public static class RepeatWholeStringServiceImpl extends GrpcSampleServiceImpl {
        @Override
        public byte[] process(byte[] query) {
            byte[] result = new byte[query.length * 2];
            System.arraycopy(query, 0, result, 0, query.length);
            System.arraycopy(query, 0, result, query.length, query.length);
            return result;
        }
    }
}
