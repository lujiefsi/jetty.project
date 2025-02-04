//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>HttpSender abstracts the algorithm to send HTTP requests, so that subclasses only
 * implement the transport-specific code to send requests over the wire, implementing
 * {@link #sendHeaders(HttpExchange, ByteBuffer, boolean, Callback)} and
 * {@link #sendContent(HttpExchange, ByteBuffer, boolean, Callback)}.</p>
 * <p>HttpSender governs the request state machines, which is updated as the various
 * steps of sending a request are executed, see {@code RequestState}.
 * At any point in time, a user thread may abort the request, which may (if the request
 * has not been completely sent yet) move the request state machine to {@code RequestState#FAILURE}.
 * The request state machine guarantees that the request steps are executed (by I/O threads)
 * only if the request has not been failed already.</p>
 *
 * @see HttpReceiver
 */
public abstract class HttpSender
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpSender.class);

    private final ContentConsumer consumer = new ContentConsumer();
    private final AtomicReference<RequestState> requestState = new AtomicReference<>(RequestState.QUEUED);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final HttpChannel channel;
    private Request.Content.Subscription subscription;

    protected HttpSender(HttpChannel channel)
    {
        this.channel = channel;
    }

    protected HttpChannel getHttpChannel()
    {
        return channel;
    }

    protected HttpExchange getHttpExchange()
    {
        return channel.getHttpExchange();
    }

    public boolean isFailed()
    {
        return requestState.get() == RequestState.FAILURE;
    }

    public void send(HttpExchange exchange)
    {
        if (!queuedToBegin(exchange))
            return;

        if (!beginToHeaders(exchange))
            return;

        demand();
    }

    protected boolean expects100Continue(Request request)
    {
        return request.getHeaders().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());
    }

    protected boolean queuedToBegin(HttpExchange exchange)
    {
        if (!updateRequestState(RequestState.QUEUED, RequestState.TRANSIENT))
            return false;

        Request request = exchange.getRequest();
        if (LOG.isDebugEnabled())
            LOG.debug("Request begin {}", request);
        RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
        notifier.notifyBegin(request);

        Request.Content body = request.getBody();

        consumer.exchange = exchange;
        consumer.expect100 = expects100Continue(request);
        subscription = body.subscribe(consumer, !consumer.expect100);

        if (updateRequestState(RequestState.TRANSIENT, RequestState.BEGIN))
            return true;

        abortRequest(exchange);
        return false;
    }

    protected boolean beginToHeaders(HttpExchange exchange)
    {
        if (!updateRequestState(RequestState.BEGIN, RequestState.TRANSIENT))
            return false;

        Request request = exchange.getRequest();
        if (LOG.isDebugEnabled())
            LOG.debug("Request headers {}{}{}", request, System.lineSeparator(), request.getHeaders().toString().trim());
        RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
        notifier.notifyHeaders(request);

        if (updateRequestState(RequestState.TRANSIENT, RequestState.HEADERS))
            return true;

        abortRequest(exchange);
        return false;
    }

    protected boolean headersToCommit(HttpExchange exchange)
    {
        if (!updateRequestState(RequestState.HEADERS, RequestState.TRANSIENT))
            return false;

        Request request = exchange.getRequest();
        if (LOG.isDebugEnabled())
            LOG.debug("Request committed {}", request);
        RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
        notifier.notifyCommit(request);

        if (updateRequestState(RequestState.TRANSIENT, RequestState.COMMIT))
            return true;

        abortRequest(exchange);
        return false;
    }

    protected boolean someToContent(HttpExchange exchange, ByteBuffer content)
    {
        RequestState current = requestState.get();
        switch (current)
        {
            case COMMIT:
            case CONTENT:
            {
                if (!updateRequestState(current, RequestState.TRANSIENT))
                    return false;

                Request request = exchange.getRequest();
                if (LOG.isDebugEnabled())
                    LOG.debug("Request content {}{}{}", request, System.lineSeparator(), BufferUtil.toDetailString(content));
                RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
                notifier.notifyContent(request, content);

                if (updateRequestState(RequestState.TRANSIENT, RequestState.CONTENT))
                    return true;

                abortRequest(exchange);
                return false;
            }
            default:
            {
                return false;
            }
        }
    }

    protected boolean someToSuccess(HttpExchange exchange)
    {
        RequestState current = requestState.get();
        switch (current)
        {
            case COMMIT:
            case CONTENT:
            {
                // Mark atomically the request as completed, with respect
                // to concurrency between request success and request failure.
                if (!exchange.requestComplete(null))
                    return false;

                requestState.set(RequestState.QUEUED);

                // Reset to be ready for another request.
                reset();

                Request request = exchange.getRequest();
                if (LOG.isDebugEnabled())
                    LOG.debug("Request success {}", request);
                HttpDestination destination = getHttpChannel().getHttpDestination();
                destination.getRequestNotifier().notifySuccess(exchange.getRequest());

                // Mark atomically the request as terminated, with
                // respect to concurrency between request and response.
                Result result = exchange.terminateRequest();
                terminateRequest(exchange, null, result);
                return true;
            }
            default:
            {
                return false;
            }
        }
    }

    private void anyToFailure(Throwable failure)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("Request failure {}", exchange.getRequest(), failure);

        // Mark atomically the request as completed, with respect
        // to concurrency between request success and request failure.
        if (exchange.requestComplete(failure))
            executeAbort(exchange, failure);
    }

    private void demand()
    {
        try
        {
            subscription.demand();
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Failure invoking demand()", x);
            anyToFailure(x);
        }
    }

    private void executeAbort(HttpExchange exchange, Throwable failure)
    {
        try
        {
            Executor executor = getHttpChannel().getHttpDestination().getHttpClient().getExecutor();
            executor.execute(() -> abort(exchange, failure));
        }
        catch (RejectedExecutionException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exchange aborted {}", exchange, x);
            abort(exchange, failure);
        }
    }

    private void abortRequest(HttpExchange exchange)
    {
        Throwable failure = this.failure.get();

        if (subscription != null)
            subscription.fail(failure);

        dispose();

        Request request = exchange.getRequest();
        if (LOG.isDebugEnabled())
            LOG.debug("Request abort {} {} on {}: {}", request, exchange, getHttpChannel(), failure);
        HttpDestination destination = getHttpChannel().getHttpDestination();
        destination.getRequestNotifier().notifyFailure(request, failure);

        // Mark atomically the request as terminated, with
        // respect to concurrency between request and response.
        Result result = exchange.terminateRequest();
        terminateRequest(exchange, failure, result);
    }

    private void terminateRequest(HttpExchange exchange, Throwable failure, Result result)
    {
        Request request = exchange.getRequest();

        if (LOG.isDebugEnabled())
            LOG.debug("Terminating request {}", request);

        if (result == null)
        {
            if (failure != null)
            {
                if (exchange.responseComplete(failure))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Response failure from request {} {}", request, exchange);
                    getHttpChannel().abortResponse(exchange, failure);
                }
            }
        }
        else
        {
            result = channel.exchangeTerminating(exchange, result);
            HttpDestination destination = getHttpChannel().getHttpDestination();
            boolean ordered = destination.getHttpClient().isStrictEventOrdering();
            if (!ordered)
                channel.exchangeTerminated(exchange, result);
            if (LOG.isDebugEnabled())
                LOG.debug("Request/Response {}: {}", failure == null ? "succeeded" : "failed", result);
            HttpConversation conversation = exchange.getConversation();
            destination.getResponseNotifier().notifyComplete(conversation.getResponseListeners(), result);
            if (ordered)
                channel.exchangeTerminated(exchange, result);
        }
    }

    /**
     * <p>Implementations should send the HTTP headers over the wire, possibly with some content,
     * in a single write, and notify the given {@code callback} of the result of this operation.</p>
     * <p>If there is more content to send, then {@link #sendContent(HttpExchange, ByteBuffer, boolean, Callback)}
     * will be invoked.</p>
     *
     * @param exchange the exchange
     * @param contentBuffer the content to send
     * @param lastContent whether the content is the last content to send
     * @param callback the callback to notify
     */
    protected abstract void sendHeaders(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent, Callback callback);

    /**
     * <p>Implementations should send the given HTTP content over the wire.</p>
     *
     * @param exchange the exchange
     * @param contentBuffer the content to send
     * @param lastContent whether the content is the last content to send
     * @param callback the callback to notify
     */
    protected abstract void sendContent(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent, Callback callback);

    protected void reset()
    {
        consumer.reset();
    }

    protected void dispose()
    {
    }

    public void proceed(HttpExchange exchange, Throwable failure)
    {
        consumer.expect100 = false;
        if (failure == null)
            demand();
        else
            anyToFailure(failure);
    }

    public boolean abort(HttpExchange exchange, Throwable failure)
    {
        // Store only the first failure.
        this.failure.compareAndSet(null, failure);

        // Update the state to avoid more request processing.
        boolean abort;
        while (true)
        {
            RequestState current = requestState.get();
            if (current == RequestState.FAILURE)
            {
                return false;
            }
            else
            {
                if (updateRequestState(current, RequestState.FAILURE))
                {
                    abort = current != RequestState.TRANSIENT;
                    break;
                }
            }
        }

        if (abort)
        {
            abortRequest(exchange);
            return true;
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Concurrent failure: request termination skipped, performed by helpers");
            return false;
        }
    }

    private boolean updateRequestState(RequestState from, RequestState to)
    {
        boolean updated = requestState.compareAndSet(from, to);
        if (!updated && LOG.isDebugEnabled())
            LOG.debug("RequestState update failed: {} -> {}: {}", from, to, requestState.get());
        return updated;
    }

    protected String relativize(String path)
    {
        try
        {
            String result = path;
            URI uri = URI.create(result);
            if (uri.isAbsolute())
                result = uri.getPath();
            return result.isEmpty() ? "/" : result;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not relativize {}", path);
            return path;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(req=%s,failure=%s)",
            getClass().getSimpleName(),
            hashCode(),
            requestState,
            failure);
    }

    /**
     * The request states {@link HttpSender} goes through when sending a request.
     */
    private enum RequestState
    {
        /**
         * One of the state transition methods is being executed.
         */
        TRANSIENT,
        /**
         * The request is queued, the initial state
         */
        QUEUED,
        /**
         * The request has been dequeued
         */
        BEGIN,
        /**
         * The request headers (and possibly some content) is about to be sent
         */
        HEADERS,
        /**
         * The request headers (and possibly some content) have been sent
         */
        COMMIT,
        /**
         * The request content is being sent
         */
        CONTENT,
        /**
         * The request is failed
         */
        FAILURE
    }

    private class ContentConsumer implements Request.Content.Consumer, Callback
    {
        private HttpExchange exchange;
        private boolean expect100;
        private ByteBuffer contentBuffer;
        private boolean lastContent;
        private Callback callback;
        private boolean committed;

        private void reset()
        {
            exchange = null;
            contentBuffer = null;
            lastContent = false;
            callback = null;
            committed = false;
        }

        @Override
        public void onContent(ByteBuffer buffer, boolean last, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Content {} last={} for {}", BufferUtil.toDetailString(buffer), last, exchange.getRequest());
            this.contentBuffer = buffer.slice();
            this.lastContent = last;
            this.callback = callback;
            if (committed)
                sendContent(exchange, buffer, last, this);
            else
                sendHeaders(exchange, buffer, last, this);
        }

        @Override
        public void onFailure(Throwable failure)
        {
            failed(failure);
        }

        @Override
        public void succeeded()
        {
            boolean proceed = false;
            if (committed)
            {
                proceed = someToContent(exchange, contentBuffer);
            }
            else
            {
                committed = true;
                if (headersToCommit(exchange))
                {
                    proceed = true;
                    // Was any content sent while committing?
                    if (contentBuffer.hasRemaining())
                        proceed = someToContent(exchange, contentBuffer);
                }
            }

            // Succeed the content callback only after emitting the request content event.
            callback.succeeded();

            // There was some concurrent error?
            if (!proceed)
                return;

            if (lastContent)
            {
                someToSuccess(exchange);
            }
            else if (expect100)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Expecting 100 Continue for {}", exchange.getRequest());
            }
            else
            {
                demand();
            }
        }

        @Override
        public void failed(Throwable x)
        {
            if (callback != null)
                callback.failed(x);
            anyToFailure(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }
    }
}
