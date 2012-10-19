/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.presto.server;

import com.facebook.presto.TupleInfo;
import com.facebook.presto.block.uncompressed.UncompressedBlock;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import io.airlift.http.client.AsyncHttpClient;
import io.airlift.http.client.Request;
import io.airlift.http.client.Response;
import io.airlift.http.client.ResponseHandler;
import io.airlift.http.client.StaticBodyGenerator;
import io.airlift.http.client.UnexpectedResponseException;

import javax.annotation.concurrent.GuardedBy;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;
import java.net.URI;
import java.util.concurrent.Future;

import static com.facebook.presto.server.PrestoMediaTypes.PRESTO_BLOCKS_TYPE;
import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.airlift.http.client.Request.Builder.preparePost;

public class HttpQuery implements QueryDriver
{
    private final String query;
    private final QueryState queryState;
    private final TupleInfo info;
    private final AsyncHttpClient httpClient;
    private final URI uri;

    @GuardedBy("this")
    private boolean done;

    @GuardedBy("this")
    private Future<Void> currentRequest;

    public HttpQuery(String query, QueryState queryState, TupleInfo info, AsyncHttpClient httpClient, URI uri)
    {
        Preconditions.checkNotNull(query, "query is null");
        Preconditions.checkNotNull(queryState, "queryState is null");
        Preconditions.checkNotNull(info, "info is null");
        Preconditions.checkNotNull(httpClient, "httpClient is null");
        Preconditions.checkNotNull(uri, "uri is null");
        this.query = query;
        this.queryState = queryState;
        this.info = info;
        this.httpClient = httpClient;
        this.uri = uri;
    }

    @Override
    public synchronized void start()
    {
        Preconditions.checkState(!done, "Query is already finished");
        Preconditions.checkState(currentRequest == null, "Query is already started");

        Request request = preparePost()
                .setUri(uri)
                .setBodyGenerator(StaticBodyGenerator.createStaticBodyGenerator(query, Charsets.UTF_8))
                .build();
        currentRequest = httpClient.execute(request, new CreateQueryResponseHandler());
    }

    @Override
    public synchronized boolean isDone()
    {
        return done;
    }

    @Override
    public synchronized void cancel()
    {
        if (!done) {
            queryState.sourceFinished();
            done = true;
            if (currentRequest != null) {
                currentRequest.cancel(true);
                currentRequest = null;
            }
        }
    }

    private synchronized void done()
    {
        if (!done) {
            queryState.sourceFinished();
            done = true;
            currentRequest = null;
        }
    }

    private synchronized void fail(Throwable throwable)
    {
        done = true;
        queryState.queryFailed(throwable);
        // todo send delete command
    }

    private synchronized void setCurrentRequest(Future<Void> currentRequest)
    {
        this.currentRequest = currentRequest;
    }

    public class CreateQueryResponseHandler implements ResponseHandler<Void, RuntimeException>
    {
        @Override
        public RuntimeException handleException(Request request, Exception exception)
        {
            fail(exception);
            throw Throwables.propagate(exception);
        }

        @Override
        public Void handle(Request request, Response response)
        {
            try {
                if (isDone()) {
                    return null;
                }

                if (response.getStatusCode() != 201) {
                    throw new UnexpectedResponseException(
                            String.format("Expected response code to be 201 CREATED, but was %d %s", response.getStatusCode(), response.getStatusMessage()),
                            request,
                            response);
                }
                String location = response.getHeader("Location");
                if (location == null) {
                    throw new UnexpectedResponseException("Response does not contain a Location header", request, response);
                }

                // query for results
                URI queryUri = URI.create(location);
                setCurrentRequest(httpClient.execute(prepareGet().setUri(queryUri).build(), new BlockResponseHandler(queryUri)));

                return null;
            }
            catch (Throwable e) {
                fail(e);
                throw Throwables.propagate(e);
            }
        }
    }

    public class BlockResponseHandler implements ResponseHandler<Void, RuntimeException>
    {
        private final URI queryUri;

        public BlockResponseHandler(URI queryUri)
        {
            this.queryUri = queryUri;
        }

        @Override
        public RuntimeException handleException(Request request, Exception exception)
        {
            fail(exception);
            throw Throwables.propagate(exception);
        }

        @Override
        public Void handle(Request request, Response response)
        {
            if (isDone()) {
                return null;
            }

            try {
                // job is finished when we get a GONE response
                if (response.getStatusCode() == Status.GONE.getStatusCode()) {
                    done();
                    return null;
                }
                if (response.getStatusCode() != Status.OK.getStatusCode()) {
                    throw new UnexpectedResponseException(
                            String.format("Expected response code to be 200, but was %d: %s", response.getStatusCode(), response.getStatusMessage()),
                            request,
                            response);
                }

                String contentType = response.getHeader("Content-Type");
                if (!MediaType.valueOf(contentType).isCompatible(PRESTO_BLOCKS_TYPE)) {
                    throw new UnexpectedResponseException(String.format("Expected %s response from server but got %s", PRESTO_BLOCKS_TYPE, contentType), request, response);
                }

                while (true) {
                    UncompressedBlock block = UncompressedBlockMapper.readBlock(info, response.getInputStream());
                    if (block == null) {
                        break;
                    }

                    queryState.addBlock(block);
                }

                setCurrentRequest(httpClient.execute(prepareGet().setUri(queryUri).build(), this));
                return null;
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(e);
                throw Throwables.propagate(e);
            }
            catch (Throwable e) {
                fail(e);
                throw Throwables.propagate(e);
            }
        }
    }
}