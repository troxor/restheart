/*
 * RESTHeart - the Web API for MongoDB
 * Copyright (C) SoftInstigate Srl
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.restheart.mongodb.handlers;

import org.restheart.handlers.exchange.RequestContext;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;

/**
 *
 * @deprecated use PipelinedWrappingHandler instead
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
@Deprecated
public class PipedWrappingHandler extends PipedHttpHandler {

    private final HttpHandler wrapped;

    /**
     * Creates a new instance of PipedWrappingHandler
     *
     * @param next
     * @param toWrap
     */
    public PipedWrappingHandler(PipedHttpHandler next, HttpHandler toWrap) {
        super(next);
        wrapped = toWrap;
    }

    /**
     *
     * @param exchange
     * @param context
     * @throws Exception
     */
    @Override
    public void handleRequest(HttpServerExchange exchange, RequestContext context) throws Exception {
        if (wrapped == null) {
            next(exchange, context);
        } else {
            wrapped.handleRequest(exchange);

            if (!exchange.isResponseComplete() && getNext() != null) {
                next(exchange, context);
            }
        }
    }
}