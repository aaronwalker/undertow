package io.undertow.server.handlers.cache;

import java.io.IOException;
import java.nio.ByteBuffer;

import io.undertow.io.IoCallback;
import io.undertow.io.Sender;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;

import static io.undertow.util.Methods.GET;
import static io.undertow.util.Methods.HEAD;

/**
 * Facade for an underlying buffer cache that contains response information.
 * <p/>
 * This facade is attached to the exchange and provides a mechanism for handlers to
 * serve cached content. By default a request to serve cached content is interpreted
 * to mean that the resulting response is cacheable, and so by default this will result
 * in the current response being cached (as long as it meets the criteria for caching).
 * <p/>
 * Calling tryServeResponse can also result in the exchange being ended with a not modified
 * response code, if the response headers indicate that this is justified (e.g. if the
 * If-Modified-Since or If-None-Match headers indicate that the client has a cached copy
 * of the response)
 * <p/>
 * This should be installed early in the handler chain, before any content encoding handlers.
 * This allows it to cache compressed copies of the response, which can significantly reduce
 * CPU load.
 * <p/>
 * NOTE: This cache has no concept of authentication, it assumes that if the underlying handler
 * indicates that a response is cachable, then the current user has been properly authenticated
 * to access that resource, and that the resource will not change per user.
 *
 * @author Stuart Douglas
 */
public class ResponseCache {

    public static final AttachmentKey<ResponseCache> ATTACHMENT_KEY = AttachmentKey.create(ResponseCache.class);

    private final DirectBufferCache<CachedHttpRequest> cache;
    private final HttpServerExchange exchange;
    private boolean responseCachable;

    public ResponseCache(final DirectBufferCache<CachedHttpRequest> cache, final HttpServerExchange exchange) {
        this.cache = cache;
        this.exchange = exchange;
    }

    /**
     * Attempts to serve the response from a cache.
     * <p/>
     * If this fails, then the response will be considered cachable, and may be cached
     * to be served by future handlers.
     * <p/>
     * If this returns true then the caller should not modify the exchange any more, as this
     * can result in a handoff to an IO thread
     *
     * @return <code>true</code> if serving suceeded,
     */
    public boolean tryServeResponse() {
        return tryServeResponse(true);
    }

    /**
     * Attempts to serve the response from a cache.
     * <p/>
     * If this fails, and the markCachable parameter is true then the response will be considered cachable,
     * and may be cached to be served by future handlers.
     * <p/>
     * If this returns true then the caller should not modify the exchange any more, as this
     * can result in a handoff to an IO thread
     *
     * @param markCacheable If this is true then the resulting response will be considered cachable
     * @return <code>true</code> if serving suceeded,
     */
    public boolean tryServeResponse(boolean markCacheable) {
        final CachedHttpRequest key = new CachedHttpRequest(exchange);
        DirectBufferCache.CacheEntry<CachedHttpRequest> entry = cache.get(key);

        //we only cache get and head requests
        if (!exchange.getRequestMethod().equals(GET) &&
                !exchange.getRequestMethod().equals(HEAD)) {
            return false;
        }

        if (entry == null) {
            this.responseCachable = markCacheable;
            return false;
        }

        // It's loading retry later
        if (!entry.enabled() || !entry.reference()) {
            this.responseCachable = markCacheable;
            return false;
        }

        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, Long.toString(entry.size()));
        if (exchange.getRequestMethod().equals(HEAD)) {
            exchange.endExchange();
            return true;
        }

        final ByteBuffer[] buffers;


        boolean ok = false;
        try {
            LimitedBufferSlicePool.PooledByteBuffer[] pooled = entry.buffers();
            buffers = new ByteBuffer[pooled.length];
            for (int i = 0; i < buffers.length; i++) {
                // Keep position from mutating
                buffers[i] = pooled[i].getResource().duplicate();
            }
            ok = true;
        } finally {
            if (!ok) {
                entry.dereference();
            }
        }

        // Transfer Inline, or register and continue transfer
        // Pass off the entry dereference call to the listener
        exchange.getResponseSender().send(buffers, new DereferenceCallback(entry));
        return true;
    }

    boolean isResponseCachable() {
        return responseCachable;
    }

    private static class DereferenceCallback implements IoCallback {
        private final DirectBufferCache.CacheEntry cache;

        public DereferenceCallback(DirectBufferCache.CacheEntry cache) {
            this.cache = cache;
        }

        @Override
        public void onComplete(final HttpServerExchange exchange, final Sender sender) {
            cache.dereference();
            exchange.endExchange();
        }

        @Override
        public void onException(final HttpServerExchange exchange, final Sender sender, final IOException exception) {
            cache.dereference();
            exchange.endExchange();
        }
    }
}