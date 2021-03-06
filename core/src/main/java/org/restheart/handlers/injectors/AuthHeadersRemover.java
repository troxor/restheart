/*
 * RESTHeart Security
 * 
 * Copyright (C) SoftInstigate Srl
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.restheart.handlers.injectors;

import io.undertow.server.HttpServerExchange;
import org.restheart.handlers.PipelinedHandler;

/**
 *
 * injects the context authenticatedAccount
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class AuthHeadersRemover extends PipelinedHandler {
    /**
     * Creates a new instance of AuthHeadersRemover
     *
     */
    public AuthHeadersRemover() {
        super();
    }

    /**
     * Creates a new instance of AuthHeadersRemover
     *
     * @param next
     */
    public AuthHeadersRemover(PipelinedHandler next) {
        super(next);
    }

    /**
     * before proxyng the request the authentication headers are removed
     *
     * @param exchange
     * @throws Exception
     */
    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        exchange.getRequestHeaders().remove("Authorization");

        next(exchange);
    }
}
