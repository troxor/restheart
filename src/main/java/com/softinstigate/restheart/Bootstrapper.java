/*
 * Copyright SoftInstigate srl. All Rights Reserved.
 *
 *
 * The copyright to the computer program(s) herein is the property of
 * SoftInstigate srl, Italy. The program(s) may be used and/or copied only
 * with the written permission of SoftInstigate srl or in accordance with the
 * terms and conditions stipulated in the agreement/contract under which the
 * program(s) have been supplied. This copyright notice must not be removed.
 */
package com.softinstigate.restheart;

import com.softinstigate.restheart.db.MongoDBClientSingleton;
import com.softinstigate.restheart.handlers.ErrorHandler;
import com.softinstigate.restheart.handlers.RequestDispacherHandler;
import com.softinstigate.restheart.handlers.SchemaEnforcerHandler;
import com.softinstigate.restheart.handlers.collections.DeleteCollectionsHandler;
import com.softinstigate.restheart.handlers.collections.GetCollectionsHandler;
import com.softinstigate.restheart.handlers.collections.PostCollectionsHandler;
import com.softinstigate.restheart.handlers.collections.PutCollectionsHandler;
import com.softinstigate.restheart.handlers.databases.DeleteDBHandler;
import com.softinstigate.restheart.handlers.databases.GetDBHandler;
import com.softinstigate.restheart.handlers.databases.PostDBHandler;
import com.softinstigate.restheart.handlers.databases.PutDBHandler;
import com.softinstigate.restheart.handlers.documents.DeleteDocumentHandler;
import com.softinstigate.restheart.handlers.documents.GetDocumentHandler;
import com.softinstigate.restheart.handlers.documents.PostDocumentHandler;
import com.softinstigate.restheart.handlers.documents.PutDocumentHandler;
import com.softinstigate.restheart.security.MapIdentityManager;
import io.undertow.Undertow;
import io.undertow.security.api.AuthenticationMechanism;
import io.undertow.security.api.AuthenticationMode;
import io.undertow.security.handlers.AuthenticationCallHandler;
import io.undertow.security.handlers.AuthenticationConstraintHandler;
import io.undertow.security.handlers.AuthenticationMechanismsHandler;
import io.undertow.security.handlers.SecurityInitialHandler;
import io.undertow.security.idm.IdentityManager;
import io.undertow.security.impl.BasicAuthenticationMechanism;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.HttpContinueAcceptingHandler;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 *
 * @author uji
 */
public class Bootstrapper
{
    private static Undertow server;
    
    static private Logger logger = LoggerFactory.getLogger(Bootstrapper.class);
    
    public static void main(final String[] args)
    {
        Yaml yaml = new Yaml();

        File confFile = null;

        if (args == null || args.length < 1)
        {
            confFile = new File("restheart.yml");
        }
        else
        {
            confFile = new File(args[0]);
        }

        Map<String, Object> conf = null;

        try
        {
            conf = (Map<String, Object>) yaml.load(new FileInputStream(confFile));
        }
        catch (FileNotFoundException ex)
        {
            System.err.println("cannot find the configuration file");
            System.exit(-2);
        }

        int port = (Integer) conf.getOrDefault("port", "443");
        boolean useEmbeddedKeystore = (Boolean) conf.getOrDefault("use-embedded-keystore", "true");
        String keystoreFile = (String) conf.get("keystore-file");
        String keystorePassword = (String) conf.get("keystore-password");
        String certPassword = (String) conf.get("certpassword");
        String mongoHost = (String) conf.getOrDefault("mongo-host", "127.0.0.1");
        int mongoPort = (Integer) conf.getOrDefault("mongo-port", 27017);
        String mongoUser = (String) conf.getOrDefault("mongo-user", "");
        String mongoPassword = (String) conf.getOrDefault("mongo-password", "");

        MongoDBClientSingleton.init(mongoHost, mongoPort, mongoUser, mongoPassword);

        logger.info("configuration {}", conf.toString());

        start(port, useEmbeddedKeystore, keystoreFile, keystorePassword, certPassword);

        Runtime.getRuntime().addShutdownHook(new Thread()
        {
            public void run()
            {
                if (server != null)
                    server.stop();
                
                logger.info("restheart stopped");
            }
        });

        logger.info("restheart started on port " + port);
    }

    private static void start(int port,
            boolean useEmbeddedKeystore,
            String keystoreFile,
            String keystorePassword,
            String certPassword)
    {
        final Map<String, char[]> users = new HashMap<>(2);
        users.put("admin", "admin".toCharArray());
        users.put("user", "user".toCharArray());

        final IdentityManager identityManager = new MapIdentityManager(users);

        SSLContext sslContext = null;

        try
        {
            KeyManagerFactory kmf;
            KeyStore ks;

            if (useEmbeddedKeystore)
            {
                char[] storepass = "restheart".toCharArray();
                char[] keypass = "restheart".toCharArray();

                String storename = "rakeystore.jks";

                sslContext = SSLContext.getInstance("TLS");
                kmf = KeyManagerFactory.getInstance("SunX509");
                ks = KeyStore.getInstance("JKS");
                ks.load(Bootstrapper.class.getClassLoader().getResourceAsStream(storename), storepass);

                kmf.init(ks, keypass);
                sslContext.init(kmf.getKeyManagers(), null, null);
            }
            else
            {
                throw new IllegalArgumentException("custom keyfactory not yet implemented");
            }
        }
        catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | CertificateException | UnrecoverableKeyException ex)
        {
            logger.error("couldn't start restheart, error with specified keystore", ex);
            System.exit(-1);
        }
        catch (FileNotFoundException ex)
        {
            logger.error("couldn't start restheart, keystore file not found" , ex);
            System.exit(-1);
        }
        catch (IOException ex)
        {
            logger.error("couldn't start restheart, error reading the keystore file", ex);
            System.exit(-1);
        }

        server = Undertow.builder()
                .addHttpsListener(port, "0.0.0.0", sslContext)
                .setWorkerThreads(50)
                .setHandler(addSecurity(
                                new ErrorHandler(
                                        new BlockingHandler(
                                                new HttpContinueAcceptingHandler(
                                                        new SchemaEnforcerHandler(
                                                                new RequestDispacherHandler(
                                                                        new GetDBHandler(), // get collections
                                                                        new PostDBHandler(), // not allowed
                                                                        new PutDBHandler(), // not allowed
                                                                        new DeleteDBHandler(), // not allowed

                                                                        new GetCollectionsHandler(), // get documents
                                                                        new PostCollectionsHandler(), // create document(s)
                                                                        new PutCollectionsHandler(), // not allowed
                                                                        new DeleteCollectionsHandler(), // delete documents

                                                                        new GetDocumentHandler(), // get document
                                                                        new PostDocumentHandler(), // not allowed !
                                                                        new PutDocumentHandler(), // create/update document
                                                                        new DeleteDocumentHandler() // delete document
                                                                )
                                                        )
                                                )
                                        )
                                ), identityManager)
                )
                .build();
        server.start();
    }

    /*
    
    
    
     new MgmtRequestDispacherHandler(
     new GetDBHandler(),             // get db metadata
     new PostDBHandler(),            // not allowed
     new PutDBHandler(),             // create / update db
     new DeleteDBHandler(),          // delete db

     new GetCollectionsHandler(),    // get collection metadata
     new PostCollectionsHandler(),   // not allowed
     new PutCollectionsHandler(),    // create / update collection
     new DeleteCollectionsHandler(), // delete collection

     new GetDocumentHandler(),       // not allowed
     new PostDocumentHandler(),      // not allowed !
     new PutDocumentHandler(),       // not allowed
     new DeleteDocumentHandler()     // not allowed
     )
    
     */
    private static void stop()
    {
        server.stop();
    }

    private static HttpHandler addSecurity(final HttpHandler toWrap, final IdentityManager identityManager)
    {
        HttpHandler handler = toWrap;
        handler = new AuthenticationCallHandler(handler);
        handler = new AuthenticationConstraintHandler(handler);
        final List<AuthenticationMechanism> mechanisms = Collections.<AuthenticationMechanism>singletonList(new BasicAuthenticationMechanism("My Realm"));
        handler = new AuthenticationMechanismsHandler(handler, mechanisms);
        handler = new SecurityInitialHandler(AuthenticationMode.PRO_ACTIVE, identityManager, handler);
        return handler;
    }
}