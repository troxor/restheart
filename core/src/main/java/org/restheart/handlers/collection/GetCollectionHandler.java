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
package org.restheart.handlers.collection;

import com.google.common.annotations.VisibleForTesting;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import io.undertow.server.HttpServerExchange;
import java.util.ArrayList;
import org.bson.BsonDocument;
import org.bson.json.JsonParseException;
import org.restheart.db.Database;
import org.restheart.db.DatabaseImpl;
import org.restheart.handlers.IllegalQueryParamenterException;
import org.restheart.handlers.PipelinedHandler;
import org.restheart.handlers.exchange.BsonRequest;
import org.restheart.handlers.exchange.BsonResponse;
import org.restheart.representation.Resource;
import org.restheart.utils.HttpStatus;
import org.restheart.utils.ResponseHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class GetCollectionHandler extends PipelinedHandler {
    private Database dbsDAO = new DatabaseImpl();

    private static final Logger LOGGER = LoggerFactory
            .getLogger(GetCollectionHandler.class);

    /**
     *
     */
    public GetCollectionHandler() {
        super();
    }

    /**
     *
     * @param next
     */
    public GetCollectionHandler(PipelinedHandler next) {
        super(next);
    }

    /**
     *
     * @param next
     * @param dbsDAO
     */
    @VisibleForTesting
    public GetCollectionHandler(PipelinedHandler next, Database dbsDAO) {
        super(next);
        this.dbsDAO = dbsDAO;
    }

    /**
     *
     * @param exchange
     * @throws Exception
     */
    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        var request = BsonRequest.wrap(exchange);
        var response = BsonResponse.wrap(exchange);
        
        if (request.isInError()) {
            next(exchange);
            return;
        }

        MongoCollection<BsonDocument> coll = dbsDAO
                .getCollection(
                        request.getDBName(),
                        request.getCollectionName());

        long size = -1;

        if (request.isCount()) {
            size = dbsDAO
                    .getCollectionSize(request.getClientSession(), 
                            coll, request.getFiltersDocument());
        }

        // ***** get data
        ArrayList<BsonDocument> data = null;

        if (request.getPagesize() > 0) {

            try {
                data = dbsDAO.getCollectionData(
                        request.getClientSession(), 
                        coll,
                        request.getPage(),
                        request.getPagesize(),
                        request.getSortByDocument(),
                        request.getFiltersDocument(),
                        request.getHintDocument(),
                        request.getProjectionDocument(),
                        request.getCursorAllocationPolicy());
            } catch (JsonParseException jpe) {
                // the filter expression is not a valid json string
                LOGGER.debug("invalid filter expression {}",
                        request.getFilter(), jpe);
                ResponseHelper.endExchangeWithMessage(
                        exchange,
                        HttpStatus.SC_BAD_REQUEST,
                        "wrong request, filter expression is invalid",
                        jpe);
                next(exchange);
                return;
            } catch (MongoException me) {
                if (me.getMessage().matches(".*Can't canonicalize query.*")) {
                    // error with the filter expression during query execution
                    LOGGER.debug(
                            "invalid filter expression {}",
                            request.getFilter(),
                            me);

                    ResponseHelper.endExchangeWithMessage(
                            exchange,
                            HttpStatus.SC_BAD_REQUEST,
                            "wrong request, filter expression is invalid",
                            me);
                    next(exchange);
                    return;
                } else {
                    throw me;
                }
            }
        }

        if (exchange.isComplete()) {
            // if an error occured getting data, the exchange is already closed
            return;
        }

        try {
            response.setContent(new CollectionRepresentationFactory()
                    .getRepresentation(exchange, data, size)
                    .asBsonDocument());

            response.setContentType(Resource.HAL_JSON_MEDIA_TYPE);
            response.setStatusCode(HttpStatus.SC_OK);

            ResponseHelper
                    .injectEtagHeader(exchange, request.getCollectionProps());

            // call the ResponseTransformerMetadataHandler if piped in
            next(exchange);
        } catch (IllegalQueryParamenterException ex) {
            ResponseHelper.endExchangeWithMessage(
                    exchange,
                    HttpStatus.SC_BAD_REQUEST,
                    ex.getMessage(),
                    ex);
            next(exchange);
        }
    }
}