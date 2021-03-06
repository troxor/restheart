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
package org.restheart.mongodb.handlers.injectors;

import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import org.apache.tika.Tika;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.json.JsonParseException;
import org.restheart.handlers.PipelinedHandler;
import org.restheart.handlers.exchange.BsonRequest;
import org.restheart.handlers.exchange.BsonResponse;
import static org.restheart.handlers.exchange.ExchangeKeys.FALSE_KEY_ID;
import static org.restheart.handlers.exchange.ExchangeKeys.FILE_METADATA;
import static org.restheart.handlers.exchange.ExchangeKeys.MAX_KEY_ID;
import static org.restheart.handlers.exchange.ExchangeKeys.MIN_KEY_ID;
import static org.restheart.handlers.exchange.ExchangeKeys.NULL_KEY_ID;
import static org.restheart.handlers.exchange.ExchangeKeys.PROPERTIES;
import static org.restheart.handlers.exchange.ExchangeKeys.TRUE_KEY_ID;
import static org.restheart.handlers.exchange.ExchangeKeys._ID;
import org.restheart.mongodb.representation.Resource;
import org.restheart.mongodb.utils.ChannelReader;
import org.restheart.utils.HttpStatus;
import org.restheart.mongodb.utils.JsonUtils;
import org.restheart.mongodb.utils.ResponseHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * injects the request body in RequestContext also check the Content-Type header
 * in case body is not empty
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class BodyInjector extends PipelinedHandler {

    static final Logger LOGGER = LoggerFactory.getLogger(BodyInjector.class);

    private static final String ERROR_INVALID_CONTENTTYPE = "Content-Type must be either: "
            + Resource.HAL_JSON_MEDIA_TYPE
            + " or " + Resource.JSON_MEDIA_TYPE;

    private static final String ERROR_INVALID_CONTENTTYPE_FILE = "Content-Type must be either: "
            + Resource.APP_FORM_URLENCODED_TYPE
            + " or " + Resource.MULTIPART_FORM_DATA_TYPE;

    private static boolean isHalOrJson(final HeaderValues contentTypes) {
        return (contentTypes == null
                || contentTypes.isEmpty())
                || (contentTypes.stream().anyMatch(ct -> ct.startsWith(Resource.HAL_JSON_MEDIA_TYPE)
                || ct.startsWith(Resource.JSON_MEDIA_TYPE)));
    }

    private static boolean isFormOrMultipart(final HeaderValues contentTypes) {
        return contentTypes != null
                && !contentTypes.isEmpty()
                && contentTypes.stream().anyMatch(ct -> ct.startsWith(Resource.APP_FORM_URLENCODED_TYPE)
                || ct.startsWith(Resource.MULTIPART_FORM_DATA_TYPE));
    }

    /**
     * Checks the _id in POST requests; it cannot be a string having a special
     * meaning e.g _null, since the URI /db/coll/_null refers to the document
     * with _id: null
     *
     * @param content
     * @return null if ok, or the first not valid id
     */
    public static String checkReservedId(BsonValue content) {
        if (content == null) {
            return null;
        } else if (content.isDocument()) {
            BsonValue id = content.asDocument().get("_id");

            if (id == null || !id.isString()) {
                return null;
            }

            String _id = id.asString().getValue();

            if (MAX_KEY_ID.equalsIgnoreCase(_id)
                    || MIN_KEY_ID.equalsIgnoreCase(_id)
                    || NULL_KEY_ID.equalsIgnoreCase(_id)
                    || TRUE_KEY_ID.equalsIgnoreCase(_id)
                    || FALSE_KEY_ID.equalsIgnoreCase(_id)) {
                return _id;
            } else {
                return null;
            }
        } else if (content.isArray()) {
            BsonArray arrayContent = content.asArray();

            Iterator<BsonValue> objs = arrayContent.getValues().iterator();

            String ret = null;

            while (objs.hasNext()) {
                BsonValue obj = objs.next();

                if (obj.isDocument()) {
                    ret = checkReservedId(obj);
                    if (ret != null) {
                        break;
                    }
                } else {
                    LOGGER.warn("element of content array is not an object");
                }
            }

            return ret;
        }

        LOGGER.warn("content is not an object nor an array");
        return null;
    }

    /**
     * Clean-up the JSON content, filtering out reserved keys
     *
     * @param content
     * @param ctx
     */
    private static void filterJsonContent(
            final BsonDocument content,
            final BsonResponse response) {
        filterOutReservedKeys(content, response);
    }

    /**
     * Filter out reserved keys, removing them from request
     *
     * The _ prefix is reserved for RESTHeart-generated properties (_id is
     * allowed)
     *
     * @param content
     * @param request
     */
    private static void filterOutReservedKeys(
            final BsonDocument content,
            final BsonResponse response) {
        final HashSet<String> keysToRemove = new HashSet<>();
        content.keySet().stream()
                .filter(key -> key.startsWith("_") && !key.equals(_ID))
                .forEach(key -> {
                    keysToRemove.add(key);
                });

        keysToRemove.stream().map(keyToRemove -> {
            content.remove(keyToRemove);
            return keyToRemove;
        }).forEach(keyToRemove -> {
            response.addWarning("Reserved field "
                    + keyToRemove
                    + " was filtered out from the request");
        });
    }

    private static void injectContentTypeFromFile(
            final BsonDocument content,
            final File file)
            throws IOException {
        if (content.get(CONTENT_TYPE) == null && file != null) {
            final String contentType = detectMediaType(file);
            if (contentType != null) {
                content.append(CONTENT_TYPE,
                        new BsonString(contentType));
            }
        }
    }

    /**
     * Search the request for a field named 'metadata' (or 'properties') which
     * must contain valid JSON
     *
     * @param formData
     * @return the parsed BsonDocument from the form data or an empty
     * BsonDocument
     */
    protected static BsonDocument extractMetadata(
            final FormData formData)
            throws JsonParseException {
        BsonDocument metadata = new BsonDocument();

        final String metadataString;

        metadataString = formData.getFirst(FILE_METADATA) != null
                ? formData.getFirst(FILE_METADATA).getValue()
                : formData.getFirst(PROPERTIES) != null
                ? formData.getFirst(PROPERTIES).getValue()
                : null;

        if (metadataString != null) {
            metadata = BsonDocument.parse(metadataString);
        }

        return metadata;
    }

    /**
     * Find the name of the first file field in this request
     *
     * @param formData
     * @return the first file field name or null
     */
    private static String extractFileField(final FormData formData) {
        String fileField = null;
        for (String f : formData) {
            if (formData.getFirst(f) != null && formData.getFirst(f).isFileItem()) {
                fileField = f;
                break;
            }
        }
        return fileField;
    }

    /**
     * Detect the file's mediatype
     *
     * @param file input file
     * @return the content-type as a String
     * @throws IOException
     */
    public static String detectMediaType(File file) throws IOException {
        return new Tika().detect(file);
    }

    private final FormParserFactory formParserFactory;

    /**
     * Creates a new instance of BodyInjectorHandler
     *
     */
    public BodyInjector() {
        this(null);
    }
    
    /**
     * Creates a new instance of BodyInjectorHandler
     *
     * @param next
     */
    public BodyInjector(PipelinedHandler next) {
        super(next);
        this.formParserFactory = FormParserFactory.builder().build();
    }

    /**
     *
     * @param exchange
     * @throws Exception
     */
    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        var request = BsonRequest.wrap(exchange);
        var response = BsonResponse.wrap(exchange);
        
        if (request.isInError()) {
            next(exchange);
            return;
        }

        if (request.isGet()
                || request.isOptions()
                || request.isDelete()) {
            next(exchange);
            return;
        }

        BsonValue content;

        final HeaderValues contentType = exchange.getRequestHeaders().get(Headers.CONTENT_TYPE);
        if (isFormOrMultipart(contentType)) {
            if (!((request.isPost() && request.isFilesBucket())
                    || (request.isPut() && request.isFile()))) {
                ResponseHelper.endExchangeWithMessage(
                        exchange,
                        HttpStatus.SC_UNSUPPORTED_MEDIA_TYPE,
                        ERROR_INVALID_CONTENTTYPE_FILE);
                next(exchange);
                return;
            }
            FormDataParser parser = this.formParserFactory.createParser(exchange);

            if (parser == null) {
                String errMsg = "There is no form parser registered "
                        + "for the request content type";

                ResponseHelper.endExchangeWithMessage(
                        exchange,
                        HttpStatus.SC_NOT_ACCEPTABLE,
                        errMsg);
                next(exchange);
                return;
            }

            FormData formData;

            try {
                formData = parser.parseBlocking();
            } catch (IOException ioe) {
                String errMsg = "Error parsing the multipart form: "
                        + "data could not be read";

                ResponseHelper.endExchangeWithMessage(
                        exchange,
                        HttpStatus.SC_NOT_ACCEPTABLE,
                        errMsg,
                        ioe);
                next(exchange);
                return;
            }

            try {
                content = extractMetadata(formData);
            } catch (JsonParseException | IllegalArgumentException ex) {
                String errMsg = "Invalid data: "
                        + "'properties' field is not a valid JSON";

                ResponseHelper.endExchangeWithMessage(
                        exchange,
                        HttpStatus.SC_NOT_ACCEPTABLE,
                        errMsg,
                        ex);
                next(exchange);
                return;
            }

            final String fileField = extractFileField(formData);

            if (fileField == null) {
                String errMsg = "This request does not contain any binary file";

                ResponseHelper.endExchangeWithMessage(
                        exchange,
                        HttpStatus.SC_NOT_ACCEPTABLE,
                        errMsg);
                next(exchange);
                return;
            }

            final Path path = formData.getFirst(fileField).getFileItem().getFile();

            request.setFilePath(path);

            injectContentTypeFromFile(content.asDocument(), path.toFile());
        } else {
            if (isHalOrJson(contentType)) {
                // get the raw content
                final String contentString = ChannelReader.read(exchange.getRequestChannel());

                // parse the json content
                if (contentString != null
                        && !contentString.isEmpty()) { // check content type

                    try {
                        content = JsonUtils.parse(contentString);

                        if (content != null
                                && !content.isDocument()
                                && !content.isArray()) {
                            throw new IllegalArgumentException(
                                    "request data must be either a json object "
                                    + "or an array"
                                    + ", got " + content.getBsonType().name());
                        }
                    } catch (JsonParseException | IllegalArgumentException ex) {
                        ResponseHelper.endExchangeWithMessage(
                                exchange,
                                HttpStatus.SC_NOT_ACCEPTABLE,
                                "Invalid JSON. " + ex.getMessage(),
                                ex);
                        next(exchange);
                        return;
                    }
                } else {
                    content = null;
                }
            } else if (contentType == null) {
                content = null;
            } else {
                ResponseHelper.endExchangeWithMessage(
                        exchange,
                        HttpStatus.SC_UNSUPPORTED_MEDIA_TYPE,
                        ERROR_INVALID_CONTENTTYPE);
                next(exchange);
                return;
            }
        }

        if (content == null) {
            content = new BsonDocument();
        } else if (content.isArray()) {
            if (!request.isCollection() || !request.isPost()) {

                ResponseHelper.endExchangeWithMessage(
                        exchange,
                        HttpStatus.SC_NOT_ACCEPTABLE,
                        "request data can be an array only "
                        + "for POST to collection resources "
                        + "(bulk post)");
                next(exchange);
                return;
            }

            if (!content.asArray().stream().anyMatch(_doc -> {
                if (_doc.isDocument()) {
                    BsonValue _id = _doc.asDocument().get(_ID);

                    if (_id != null && _id.isArray()) {
                        String errMsg = "the type of _id in request data"
                                + " is not supported: "
                                + (_id == null
                                        ? ""
                                        : _id.getBsonType().name());

                        ResponseHelper.endExchangeWithMessage(
                                exchange,
                                HttpStatus.SC_NOT_ACCEPTABLE,
                                errMsg);

                        return false;
                    }
                    filterJsonContent(_doc.asDocument(), response);
                    return true;
                } else {
                    String errMsg = "request data must be either "
                            + "an json object or an array of objects";

                    ResponseHelper.endExchangeWithMessage(
                            exchange,
                            HttpStatus.SC_NOT_ACCEPTABLE,
                            errMsg);
                    return false;
                }
            })) {
                // an error occurred
                next(exchange);
                return;
            }
        } else if (content.isDocument()) {
            BsonDocument _content = content.asDocument();

            BsonValue _id = _content.get(_ID);

            if (_id != null && _id.isArray()) {
                String errMsg = "the type of _id in request data "
                        + "is not supported: "
                        + _id.getBsonType().name();

                ResponseHelper.endExchangeWithMessage(
                        exchange,
                        HttpStatus.SC_NOT_ACCEPTABLE,
                        errMsg);
                next(exchange);
                return;
            }

            filterJsonContent(_content, response);
        }

        if (request.isPost() || request.isPut()) {
            if (JsonUtils.containsUpdateOperators(content, true)) {
                // not acceptable
                String errMsg = "update operators (but $currentDate) cannot be used on POST and PUT requests";

                ResponseHelper.endExchangeWithMessage(
                        exchange,
                        HttpStatus.SC_BAD_REQUEST,
                        errMsg);
                next(exchange);
                return;
            }

            // flatten request content for POST and PUT requests
            content = JsonUtils.unflatten(content);
        }

        request.setContent(content);

        next(exchange);
    }
}
