package org.example;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * LLM 网关调用的瞬时故障重试：网络中断/超时与 HTTP 429、5xx 自动重试（退避 1s、2s、4s），最多 3 次；
 * 4xx 属请求本身有误，不重试直接上抛。供 anthropic / openai 协议客户端共用。
 */
final class HttpRetry {

    /** 网络级重试次数（退避 1s、2s、4s），仍失败才把错误抛给上层。 */
    private static final int MAX_RETRIES = 3;

    private HttpRetry() {
    }

    /** 网关返回非 200；携带状态码供可重试判断。 */
    static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String body) {
            super("HTTP " + status + ": " + body);
            this.status = status;
        }
    }

    /** 单次尝试；HTTP 发送与 SSE 消费过程中的检查异常原样抛出。 */
    @FunctionalInterface
    interface Attempt<T> {
        T get() throws Exception;
    }

    static <T> T retry(Attempt<T> attempt) throws Exception {
        for (int i = 0; ; i++) {
            try {
                return attempt.get();
            } catch (HttpError | IOException | UncheckedIOException e) {
                if (i == MAX_RETRIES || !retryable(e)) {
                    throw e;
                }
                System.out.println(Console.warn("[llm] " + brief(e) + "，" + (1000L << i) + "ms 后第 "
                        + (i + 2) + " 次尝试…"));
                Thread.sleep(1000L << i);
            }
        }
    }

    /** 网络异常可重试；HTTP 错误仅 429 与 5xx（网关瞬时故障，如「网络错误，请稍后重试」）可重试。 */
    private static boolean retryable(Exception e) {
        return !(e instanceof HttpError err) || err.status == 429 || err.status >= 500;
    }

    /** 重试提示只留一行：异常消息截断，避免网关错误体刷屏。 */
    private static String brief(Exception e) {
        String msg = e.getMessage();
        return msg == null ? e.getClass().getSimpleName() : msg.length() > 160 ? msg.substring(0, 160) + "…" : msg;
    }
}
