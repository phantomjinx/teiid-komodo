/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.komodo.rest;

import static org.komodo.rest.Messages.Error.KOMODO_ENGINE_CLEAR_TIMEOUT;
import static org.komodo.rest.Messages.Error.KOMODO_ENGINE_SHUTDOWN_ERROR;
import static org.komodo.rest.Messages.Error.KOMODO_ENGINE_SHUTDOWN_TIMEOUT;
import static org.komodo.rest.Messages.Error.KOMODO_ENGINE_STARTUP_TIMEOUT;
import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.PreDestroy;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.komodo.core.KEngine;
import org.komodo.core.repository.SynchronousCallback;
import org.komodo.importer.ImportMessages;
import org.komodo.importer.ImportOptions;
import org.komodo.importer.ImportOptions.ExistingNodeOptions;
import org.komodo.importer.ImportOptions.OptionKeys;
import org.komodo.relational.connection.Connection;
import org.komodo.relational.dataservice.Dataservice;
import org.komodo.relational.dataservice.internal.DataserviceConveyor;
import org.komodo.relational.importer.vdb.VdbImporter;
import org.komodo.relational.resource.Driver;
import org.komodo.relational.vdb.Vdb;
import org.komodo.relational.workspace.WorkspaceManager;
import org.komodo.rest.KomodoRestV1Application.V1Constants;
import org.komodo.rest.cors.KCorsFactory;
import org.komodo.rest.cors.KCorsHandler;
import org.komodo.rest.cors.OptionsExceptionMapper;
import org.komodo.rest.json.JsonConstants;
import org.komodo.rest.service.KomodoConnectionService;
import org.komodo.rest.service.KomodoDataserviceService;
import org.komodo.rest.service.KomodoDriverService;
import org.komodo.rest.service.KomodoImportExportService;
import org.komodo.rest.service.KomodoMetadataService;
import org.komodo.rest.service.KomodoSearchService;
import org.komodo.rest.service.KomodoUtilService;
import org.komodo.rest.service.KomodoVdbService;
import org.komodo.rest.swagger.RestDataserviceConverter;
import org.komodo.rest.swagger.RestPropertyConverter;
import org.komodo.rest.swagger.RestVdbConditionConverter;
import org.komodo.rest.swagger.RestVdbConverter;
import org.komodo.rest.swagger.RestVdbDataRoleConverter;
import org.komodo.rest.swagger.RestVdbImportConverter;
import org.komodo.rest.swagger.RestVdbMaskConverter;
import org.komodo.rest.swagger.RestVdbModelConverter;
import org.komodo.rest.swagger.RestVdbModelSourceConverter;
import org.komodo.rest.swagger.RestVdbPermissionConverter;
import org.komodo.rest.swagger.RestVdbTranslatorConverter;
import org.komodo.spi.KEvent.Type;
import org.komodo.spi.constants.StringConstants;
import org.komodo.spi.constants.SystemConstants;
import org.komodo.spi.lexicon.vdb.VdbLexicon;
import org.komodo.spi.logging.KLogger.Level;
import org.komodo.spi.metadata.MetadataInstance;
import org.komodo.spi.repository.KomodoObject;
import org.komodo.spi.repository.Repository;
import org.komodo.spi.repository.Repository.UnitOfWork;
import org.komodo.spi.repository.RepositoryClientEvent;
import org.komodo.utils.KLog;
import org.komodo.utils.observer.KLatchObserver;
import io.swagger.converter.ModelConverters;
import io.swagger.jaxrs.config.BeanConfig;

/**
 * The JAX-RS {@link Application} that provides the Komodo REST API.
 */
@ApplicationPath( V1Constants.APP_PATH )
public class KomodoRestV1Application extends Application implements StringConstants {

    /**
     * Constants associated with version 1 of the Komodo REST application.
     */
    public static interface V1Constants extends JsonConstants {

        class App {

            private static final Properties properties = new Properties();

            private static void init() {
                InputStream fileStream = KomodoRestV1Application.class.getClassLoader().getResourceAsStream("app.properties");

                try {
                    properties.load(fileStream);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }

            /**
             * Application name and context
             */
            public static String name() {
                init();

                return properties.getProperty("app.name");
            }

            /**
             * Application display title
             */
            public static String title() {
                init();

                return properties.getProperty("app.title");
            }

            /**
             * Application description
             */
            public static String description() {
                init();

                return properties.getProperty("app.description");
            }

            /**
             * Version of the application
             */
            public static String version() {
                init();

                return properties.getProperty("app.version");
            }
        }

        /**
         * Jboss server base directory
         */
        String JBOSS_SERVER_BASE_DIR = "jboss.server.base.dir"; //$NON-NLS-1$

        /**
         * Location for the log file passed to {@link KLog} logger
         */
        String LOG_FILE_PATH = "log/vdb-builder.log"; //$NON-NLS-1$

        /**
         * The URI path segment for the Komodo REST application. It is included in the base URI. <strong>DO NOT INCLUDE THIS IN
         * OTHER URI SEGMENTS</strong>
         */
        String APP_PATH = FORWARD_SLASH + "v1"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the Komodo workspace.
         */
        String WORKSPACE_SEGMENT = "workspace"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the utility service.
         */
        String SERVICE_SEGMENT = "service"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the metadata service.
         */
        String METADATA_SEGMENT = "metadata"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the Komodo schema.
         */
        String SCHEMA_SEGMENT = "schema"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the VDB manifest XML resource.
         */
        String VDB_MANIFEST_SEGMENT = "manifest"; //$NON-NLS-1$

        /**
         * The about segment
         */
        String ABOUT = "about"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for a Vdb in the Komodo workspace.
         */
        String VDB_SEGMENT = "vdb"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the collection of VDBs in the Komodo workspace.
         */
        String VDBS_SEGMENT = "vdbs"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific vdb id
         */
        String VDB_PLACEHOLDER = "{vdbName}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for clone.
         */
        String CLONE_SEGMENT = "clone"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for creating workspace VDBs from teiid
         */
        String VDBS_FROM_TEIID = "VdbsFromTeiid"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for creating workspace connections from teiid
         */
        String CONNECTIONS_FROM_TEIID = "connectionsFromTeiid"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for undeploy.
         */
        String UNDEPLOY = "undeploy"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the collection of DataServices in the Komodo workspace.
         */
        String DATA_SERVICES_SEGMENT = "dataservices"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for a DataService in the Komodo workspace.
         */
        String DATA_SERVICE_SEGMENT = "dataservice"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific data service id
         */
        String DATA_SERVICE_PLACEHOLDER = "{dataserviceName}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for DataService deployable status
         */
        String DEPLOYABLE_STATUS_SEGMENT = "deployableStatus"; //$NON-NLS-1$
        
        /**
         * The name of the URI path segment for validating a data service or connection name.
         */
        String NAME_VALIDATION_SEGMENT = "nameValidation"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for finding source vdb matches for a DataService
         */
        String SOURCE_VDB_MATCHES = "sourceVdbMatches"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for finding service view info for a DataService
         */
        String SERVICE_VIEW_INFO = "serviceViewInfo"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for connections.
         */
        String CONNECTIONS_SEGMENT = "connections"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for a connection.
         */
        String CONNECTION_SEGMENT = "connection"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific connection id
         */
        String CONNECTION_PLACEHOLDER = "{connectionName}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for templates.
         */
        String TEMPLATES_SEGMENT = "templates"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for a template.
         */
        String TEMPLATE_SEGMENT = "template"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific template id
         */
        String TEMPLATE_PLACEHOLDER = "{templateName}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for template entries.
         */
        String TEMPLATE_ENTRIES_SEGMENT = "entries"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific template entry id
         */
        String TEMPLATE_ENTRY_PLACEHOLDER = "{templateEntryName}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for a setting a dataservice's service vdb for single table view
         */
        String SERVICE_VDB_FOR_SINGLE_TABLE = "ServiceVdbForSingleTable"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for a setting a dataservice's service vdb for join view
         */
        String SERVICE_VDB_FOR_JOIN_TABLES = "ServiceVdbForJoinTables"; //$NON-NLS-1$
        
        /**
         * The name of the URI path segment for getting the DDL for single table view
         */
        String SERVICE_VIEW_DDL_FOR_SINGLE_TABLE = "ServiceViewDdlForSingleTable"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for getting the DDL for join view
         */
        String SERVICE_VIEW_DDL_FOR_JOIN_TABLES = "ServiceViewDdlForJoinTables"; //$NON-NLS-1$
        
        /**
         * The name of the URI path segment for getting the join criteria given two tables
         */
        String CRITERIA_FOR_JOIN_TABLES = "CriteriaForJoinTables"; //$NON-NLS-1$
        
        /**
         * The name of the URI path segment for the collection of Drivers in the Komodo workspace.
         */
        String DRIVERS_SEGMENT = "drivers"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the collection of models of a vdb
         */
        String MODELS_SEGMENT = "Models"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific model id
         */
        String MODEL_PLACEHOLDER = "{modelName}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the collection of sources of a model
         */
        String SOURCES_SEGMENT = "VdbModelSources"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific source id
         */
        String SOURCE_PLACEHOLDER = "{sourceName}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the collection of catalogs
         */
        String JDBC_CATALOG_SCHEMA_SEGMENT = "JdbcCatalogSchema"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the jdbc info
         */
        String JDBC_INFO_SEGMENT = "JdbcInfo"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the collection of tables of a model
         */
        String TABLES_SEGMENT = "Tables"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific table id
         */
        String TABLE_PLACEHOLDER = "{tableName}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the collection of columns of a table
         */
        String COLUMNS_SEGMENT = "Columns"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for default translator
         */
        String TRANSLATOR_DEFAULT_SEGMENT = "TranslatorDefault"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the collection of translators of a vdb
         */
        String TRANSLATORS_SEGMENT = "VdbTranslators"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific translator id
         */
        String TRANSLATOR_PLACEHOLDER = "{translatorName}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the collection of imports of a vdb
         */
        String IMPORTS_SEGMENT = "VdbImports"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific import id
         */
        String IMPORT_PLACEHOLDER = "{importName}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the collection of data roles of a vdb
         */
        String DATA_ROLES_SEGMENT = "VdbDataRoles"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific data role id
         */
        String DATA_ROLE_PLACEHOLDER = "{dataRoleId}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the collection of data role permissions
         */
        String PERMISSIONS_SEGMENT = "VdbPermissions"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific permission id
         */
        String PERMISSION_PLACEHOLDER = "{permissionId}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the collection of permission's conditions
         */
        String CONDITIONS_SEGMENT = "VdbConditions"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific condition id
         */
        String CONDITION_PLACEHOLDER = "{conditionId}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for the collection of permission's masks
         */
        String MASKS_SEGMENT = "VdbMasks"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific mask id
         */
        String MASK_PLACEHOLDER = "{maskId}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for loading of the sample vdb data
         */
        String SAMPLE_DATA = "samples"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for validating a value
         */
        String VALIDATE_SEGMENT = "validate"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI for validation of the value
         */
        String VALIDATE_PLACEHOLDER = "{validateValue}"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for searching the workspace
         */
        String SEARCH_SEGMENT = "search"; //$NON-NLS-1$

        /**
         * The name of the URI search saved search parameter
         */
        String SEARCH_SAVED_NAME_PARAMETER = "searchName"; //$NON-NLS-1$

        /**
         * The name of the URI search contains parameter
         */
        String SEARCH_CONTAINS_PARAMETER = "contains"; //$NON-NLS-1$

        /**
         * The name of the URI search name parameter
         */
        String SEARCH_OBJECT_NAME_PARAMETER = "objectName"; //$NON-NLS-1$

        /**
         * The name of the URI search path parameter
         */
        String SEARCH_PATH_PARAMETER = "path"; //$NON-NLS-1$

        /**
         * The name of the URI search path parameter
         */
        String SEARCH_TYPE_PARAMETER = "type"; //$NON-NLS-1$

        /**
         * The name of the URI search parent parameter
         */
        String SEARCH_PARENT_PARAMETER = "parent"; //$NON-NLS-1$

        /**
         * The name of the URI search ancestor parameter
         */
        String SEARCH_ANCESTOR_PARAMETER = "ancestor"; //$NON-NLS-1$

        /**
         * The URI path for the collection of saved searches
         */
        String SAVED_SEARCHES_SEGMENT = "savedSearches"; //$NON-NLS-1$

        /**
         * The name of the URI vdb name parameter
         */
        String VDB_NAME_PARAMETER = "name"; //$NON-NLS-1$

        /**
         * The vdb export xml property
         */
        String VDB_EXPORT_XML_PROPERTY = "vdb-export-xml"; //$NON-NLS-1$

        /**
         * The name of the URI path segment for creating a workspace vdb model using teiid ddl
         */
        String MODEL_FROM_TEIID_DDL = "ModelFromTeiidDdl"; //$NON-NLS-1$

        /**
         * The teiid credentials property for modifying the usernames and passwords
         */
        String METADATA__CREDENTIALS = "credentials"; //$NON-NLS-1$

        /**
         * The driver property for adding a driver to the teiid server
         */
        String METADATA_DRIVER = "driver"; //$NON-NLS-1$

        /**
         * Placeholder added to an URI to allow a specific teiid driver id
         */
        String METADATA_DRIVER_PLACEHOLDER = "{driverName}"; //$NON-NLS-1$

        /**
         * The teiid status path segment
         */
        String STATUS_SEGMENT = "status"; //$NON-NLS-1$

        /**
         * The name of the resource used for importing and exporting artifacts
         */
        String IMPORT_EXPORT_SEGMENT = "importexport"; //$NON-NLS-1$

        /**
         * The export operation of the import export service
         */
        String EXPORT = "export"; //$NON-NLS-1$

        /**
         * The import operation of the import export service
         */
        String IMPORT = "import"; //$NON-NLS-1$

        /**
         * The available storage types of the import export service
         */
        String STORAGE_TYPES = "availableStorageTypes"; //$NON-NLS-1$

        /**
         * The teiid segment for running a query against the teiid server
         */
        String QUERY_SEGMENT = "query"; //$NON-NLS-1$

        /**
         * The teiid segment for running a ping against the teiid server
         */
        String PING_SEGMENT = "ping"; //$NON-NLS-1$

        /**
         * The name of the URI ping type parameter
         */
        String PING_TYPE_PARAMETER = "pingType"; //$NON-NLS-1$
    }

    private static final int TIMEOUT = 1;
    private static final TimeUnit UNIT = TimeUnit.MINUTES;

    private KEngine kengine;
    private final Set< Object > singletons;

    /**
     * Constructs a Komodo REST application.
     *
     * @throws WebApplicationException
     *         if the Komodo engine cannot be started
     */
    public KomodoRestV1Application() throws WebApplicationException {
        KCorsHandler corsHandler;
        try {
            // Set the log path to something relative to the deployment location of this application
            // Try to use the base directory in jboss. If not in jboss this would probably be empty so
            // otherwise use "." relative to the working directory
            String baseDir = System.getProperty(V1Constants.JBOSS_SERVER_BASE_DIR, DOT) + File.separator;

            // Set the komodo data directory prior to starting the engisne
            String komodoDataDir = System.getProperty(SystemConstants.ENGINE_DATA_DIR);
            if (komodoDataDir == null)
                System.setProperty(SystemConstants.ENGINE_DATA_DIR, baseDir + "data"); //$NON-NLS-1$

            // Set the log file path
            KLog.getLogger().setLogPath(baseDir + V1Constants.LOG_FILE_PATH);

            // Ensure server logging level is reduced to something sane!
            KLog.getLogger().setLevel(Level.INFO);

            corsHandler = initCorsHandler();
        } catch (Exception ex) {
            throw new WebApplicationException(ex, Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        }

        this.kengine = start();

        final Set< Object > objs = new HashSet< >();
        objs.add( new KomodoExceptionMapper() );
        objs.add( new KomodoUtilService( this.kengine ) );
        objs.add( new KomodoDataserviceService( this.kengine ) );
        objs.add( new KomodoConnectionService( this.kengine ) );
        objs.add( new KomodoDriverService( this.kengine ) );
        objs.add( new KomodoVdbService( this.kengine ) );
        objs.add( new KomodoSearchService( this.kengine ));
        objs.add( new KomodoMetadataService( this.kengine ));
        objs.add( new KomodoImportExportService( this.kengine ));

        objs.add(new OptionsExceptionMapper());
        objs.add(corsHandler);

        this.singletons = Collections.unmodifiableSet( objs );

        initSwaggerConfiguration();
    }

    private KCorsHandler initCorsHandler() throws Exception {
        KCorsHandler corsHandler = KCorsFactory.getInstance().createHandler();
        corsHandler.getAllowedOrigins().add(STAR);
        corsHandler.setAllowedHeaders(KCorsHandler.ALLOW_HEADERS);
        corsHandler.setAllowCredentials(true);
        corsHandler.setAllowedMethods(KCorsHandler.ALLOW_METHODS);
        corsHandler.setCorsMaxAge(1209600);
        return corsHandler;
    }

    @SuppressWarnings( "nls" )
    private void initSwaggerConfiguration() {
        //
        // Add converters for display of definitions
        //
        ModelConverters converters = ModelConverters.getInstance();
        converters.addConverter(new RestPropertyConverter());
        converters.addConverter(new RestVdbConditionConverter());
        converters.addConverter(new RestVdbConverter());
        converters.addConverter(new RestVdbDataRoleConverter());
        converters.addConverter(new RestVdbImportConverter());
        converters.addConverter(new RestVdbMaskConverter());
        converters.addConverter(new RestVdbModelConverter());
        converters.addConverter(new RestVdbModelSourceConverter());
        converters.addConverter(new RestVdbPermissionConverter());
        converters.addConverter(new RestVdbTranslatorConverter());
        converters.addConverter(new RestDataserviceConverter());

        BeanConfig beanConfig = new BeanConfig();
        beanConfig.setTitle(V1Constants.App.title());
        beanConfig.setDescription(V1Constants.App.description());
        beanConfig.setVersion(V1Constants.App.version());
        beanConfig.setSchemes(new String[]{"https"});
        beanConfig.setBasePath(V1Constants.App.name() + V1Constants.APP_PATH);

        // No need to setHost as it will pick up the one its running on

        beanConfig.setResourcePackage(
                                      RestProperty.class.getPackage().getName() + COMMA +
                                      KomodoVdbService.class.getPackage().getName());
        beanConfig.setPrettyPrint(true);
        beanConfig.setScan(true);
    }

    /**
     * @return the default repository of this application.
     *            Should only be applicable for testing.
     *
     */
    public Repository getDefaultRepository() {
        return kengine.getDefaultRepository();
    }

    /**
     * Clears the Komodo default repository.
     *
     * @throws WebApplicationException
     *         if an error occurs clearing the repository
     */
    public void clearRepository() throws WebApplicationException {
        final RepositoryClientEvent event = RepositoryClientEvent.createClearEvent( this.kengine );
        this.kengine.getDefaultRepository().notify( event );

        KLatchObserver observer = new KLatchObserver(Type.REPOSITORY_CLEARED);
        this.kengine.addObserver(observer);

        // wait for repository to clear
        boolean cleared = false;

        try {
            cleared = observer.getLatch().await( TIMEOUT, UNIT );
        } catch ( final Exception e ) {
            throw new WebApplicationException( e, Status.INTERNAL_SERVER_ERROR );
        } finally {
            this.kengine.removeObserver(observer);
        }

        if ( !cleared ) {
            throw new WebApplicationException( new Exception(Messages.getString( KOMODO_ENGINE_CLEAR_TIMEOUT, TIMEOUT, UNIT )),
                                            Status.INTERNAL_SERVER_ERROR );
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see javax.ws.rs.core.Application#getSingletons()
     */
    @Override
    public Set< Object > getSingletons() {
        return this.singletons;
    }

    @Override
    public Set<Class<?>> getClasses() {
        Set<Class<?>> resources = new HashSet<Class<?>>();

        // Enable swagger support
        resources.add(io.swagger.jaxrs.listing.ApiListingResource.class);
        resources.add(io.swagger.jaxrs.listing.SwaggerSerializers.class);

        return resources;
    }

    private KEngine start() throws WebApplicationException {
        final KEngine kengine = KEngine.getInstance();

        boolean started;
        try {
           started = kengine.startAndWait();
        } catch (Exception e) {
            throw new WebApplicationException( e, Status.INTERNAL_SERVER_ERROR );
        }

        if ( !started ) {
            throw new WebApplicationException( new Exception(Messages.getString( KOMODO_ENGINE_STARTUP_TIMEOUT, TIMEOUT, UNIT )),
                                            Status.INTERNAL_SERVER_ERROR );
        }

        return kengine;
    }

    /**
     * Stops the Komodo Engine.
     *
     * @throws WebApplicationException
     *         if there is a problem shutting down the Komodo engine
     */
    @PreDestroy
    public void stop() throws WebApplicationException {
        if (this.kengine == null)
            return;

        KLatchObserver observer = new KLatchObserver(Type.ENGINE_SHUTDOWN);
        this.kengine.addObserver(observer);

        // wait for repository to shutdown
        boolean shutdown = false;

        try {
            this.kengine.shutdownAndWait();
            shutdown = observer.getLatch().await(TIMEOUT, UNIT);
        } catch (final Exception e) {
            throw new WebApplicationException(new Exception(Messages.getString(KOMODO_ENGINE_SHUTDOWN_ERROR)),
                                              Status.INTERNAL_SERVER_ERROR);
        } finally {
            this.kengine = null;
        }

        if (!shutdown) {
            throw new WebApplicationException(new Exception(Messages.getString(KOMODO_ENGINE_SHUTDOWN_TIMEOUT, TIMEOUT, UNIT)),
                                              Status.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Import a vdb into the komodo engine
     *
     * @param vdbStream vdb input stream
     * @param user initiating import
     *
     * @throws Exception if error occurs
     */
    public void importVdb(InputStream vdbStream, String user) throws Exception {
        Repository repository = this.kengine.getDefaultRepository();

        SynchronousCallback callback = new SynchronousCallback();
        UnitOfWork uow = repository.createTransaction(user, "Import Vdb", false, callback); //$NON-NLS-1$

        ImportOptions importOptions = new ImportOptions();
        ImportMessages importMessages = new ImportMessages();

        KomodoObject workspace = repository.komodoWorkspace(uow);
        VdbImporter importer = new VdbImporter(repository);
        importer.importVdb(uow, vdbStream, workspace, importOptions, importMessages);
        uow.commit();
        callback.await(3, TimeUnit.MINUTES);
    }

    /**
     * Import a dataservice into the komodo engine
     *
     * @param dsStream dataservice input stream
     * @param user initiating import
     *
     * @throws Exception if error occurs
     */
    public void importDataservice(InputStream dsStream, String user) throws Exception {
        Repository repository = this.kengine.getDefaultRepository();
        MetadataInstance metadataInstance = this.kengine.getMetadataInstance();

        SynchronousCallback callback = new SynchronousCallback();
        UnitOfWork uow = repository.createTransaction(user, "Import Dataservice", false, callback); //$NON-NLS-1$

        ImportOptions importOptions = new ImportOptions();
        ImportMessages importMessages = new ImportMessages();

        KomodoObject workspace = repository.komodoWorkspace(uow);
        DataserviceConveyor dsConveyor = new DataserviceConveyor(repository, metadataInstance);
        dsConveyor.dsImport(uow, dsStream, workspace, importOptions, importMessages);
        uow.commit();
        callback.await(3, TimeUnit.MINUTES);
    }

    /**
     * @param user initiating call
     *
     * @return the vdbs directly from the kEngine
     * @throws Exception if error occurs
     */
    public Vdb[] getVdbs(String user) throws Exception {
        Repository repository = this.kengine.getDefaultRepository();

        UnitOfWork uow = repository.createTransaction(user, "Find vdbs", true, null); //$NON-NLS-1$
        WorkspaceManager mgr = WorkspaceManager.getInstance(repository, uow);
        Vdb[] vdbs = mgr.findVdbs(uow);

        uow.commit();

        return vdbs;
    }

    /**
     * Create a dataservice in the komodo engine (used for mostly test purposes)
     *
     * @param dataserviceName the service name
     * @param populateWithSamples true if dataservice should be populated with example vdbs
     * @param user initiating transaction
     * @throws Exception if error occurs
     */
    public void createDataservice(String dataserviceName, boolean populateWithSamples, String user) throws Exception {
        Repository repository = this.kengine.getDefaultRepository();

        SynchronousCallback callback = new SynchronousCallback();
        UnitOfWork uow = repository.createTransaction(user, "Create Dataservice", false, callback); //$NON-NLS-1$

        KomodoObject wkspace = repository.komodoWorkspace(uow);
        WorkspaceManager wsMgr = WorkspaceManager.getInstance(repository, uow);

        VdbImporter importer = new VdbImporter(repository);
        ImportMessages importMessages = new ImportMessages();
        ImportOptions importOptions = new ImportOptions();
        importOptions.setOption(OptionKeys.HANDLE_EXISTING, ExistingNodeOptions.RETURN);

        String portfolioSample = KomodoUtilService.SAMPLES[1];
        String nwSample = KomodoUtilService.SAMPLES[4];
        InputStream portSampleStream = KomodoUtilService.getVdbSample(portfolioSample);
        InputStream nwindSampleStream = KomodoUtilService.getVdbSample(nwSample);

        importer.importVdb(uow, portSampleStream, wkspace, importOptions, importMessages);
        importer.importVdb(uow, nwindSampleStream, wkspace, importOptions, importMessages);

        KomodoObject pfSampleObj = wkspace.getChild(uow, "Portfolio");
        Vdb pfVdb = wsMgr.resolve(uow, pfSampleObj, Vdb.class);

        KomodoObject nwSampleObj = wkspace.getChild(uow, "Northwind");
        Vdb nwVdb = wsMgr.resolve(uow, nwSampleObj, Vdb.class);

        Dataservice dataservice = wsMgr.createDataservice(uow, wkspace, dataserviceName);
        dataservice.setDescription(uow, "This is my dataservice");

        dataservice.addVdb(uow, pfVdb);
        dataservice.setServiceVdb(uow, nwVdb);

        uow.commit();
        callback.await(3, TimeUnit.MINUTES);
    }

    /**
     * @param user initiating call
     *
     * @return the dataservices directly from the kEngine
     * @throws Exception if error occurs
     */
    public Dataservice[] getDataservices(String user) throws Exception {
        Repository repository = this.kengine.getDefaultRepository();

        UnitOfWork uow = repository.createTransaction(user, "Find dataservices", true, null); //$NON-NLS-1$
        WorkspaceManager mgr = WorkspaceManager.getInstance(repository, uow);
        Dataservice[] services = mgr.findDataservices(uow);
        uow.commit();

        return services;
    }
    
    /**
     * Create a Vdb in the komodo engine
     *
     * @param vdbName the vdb name
     * @param user initiating call
     * @throws Exception if error occurs
     */
    public void createVdb(String vdbName, String user) throws Exception {
        Repository repository = this.kengine.getDefaultRepository();

        SynchronousCallback callback = new SynchronousCallback();
        UnitOfWork uow = repository.createTransaction(user, "Create VDB", false, callback); //$NON-NLS-1$

        WorkspaceManager wsMgr = WorkspaceManager.getInstance(repository, uow);
        wsMgr.createVdb(uow, null, vdbName, vdbName);

        uow.commit();
        callback.await(3, TimeUnit.MINUTES);
    }

    /**
     * Create a Model within a vdb in the komodo engine
     *
     * @param vdbName the vdb name
     * @param modelName the vdb name
     * @param user initiating call
     * @throws Exception if error occurs
     */
    public void createVdbModel(String vdbName, String modelName, String user) throws Exception {
        Repository repository = this.kengine.getDefaultRepository();

        SynchronousCallback callback = new SynchronousCallback();
        UnitOfWork uow = repository.createTransaction(user, "Create Model", false, callback); //$NON-NLS-1$

        WorkspaceManager wsMgr = WorkspaceManager.getInstance(repository, uow);
        if(!wsMgr.hasChild(uow, vdbName, VdbLexicon.Vdb.VIRTUAL_DATABASE)) {
            wsMgr.createVdb(uow, null, vdbName, vdbName);
        }
        
        KomodoObject kobj = wsMgr.getChild(uow, vdbName, VdbLexicon.Vdb.VIRTUAL_DATABASE);
        Vdb vdb = Vdb.RESOLVER.resolve(uow, kobj);
        
        vdb.addModel(uow, modelName);

        uow.commit();
        callback.await(3, TimeUnit.MINUTES);
    }

    /**
     * Create a connection in the komodo engine
     *
     * @param connectionName the connection name
     * @param user initiating call
     * @throws Exception if error occurs
     */
    public void createConnection(String connectionName, String user) throws Exception {
        Repository repository = this.kengine.getDefaultRepository();

        SynchronousCallback callback = new SynchronousCallback();
        UnitOfWork uow = repository.createTransaction(user, "Create Connection", false, callback); //$NON-NLS-1$

        WorkspaceManager wsMgr = WorkspaceManager.getInstance(repository, uow);
        wsMgr.createConnection(uow, null, connectionName);

        uow.commit();
        callback.await(3, TimeUnit.MINUTES);
    }

    /**
     * @param user initiating call
     *
     * @return the connections directly from the kEngine
     * @throws Exception if error occurs
     */
    public Connection[] getConnections(String user) throws Exception {
        Repository repository = this.kengine.getDefaultRepository();

        UnitOfWork uow = repository.createTransaction(user, "Find connections", true, null); //$NON-NLS-1$
        WorkspaceManager mgr = WorkspaceManager.getInstance(repository, uow);
        Connection[] sources = mgr.findConnections(uow);
        uow.commit();

        return sources;
    }
    
    /**
     * Create a Driver in the komodo engine
     *
     * @param driverName the driver name
     * @throws Exception if error occurs
     */
    public void createDriver(String driverName) throws Exception {
        Repository repository = this.kengine.getDefaultRepository();

        SynchronousCallback callback = new SynchronousCallback();
        UnitOfWork uow = repository.createTransaction(Repository.SYSTEM_USER, "Create Driver", false, callback); //$NON-NLS-1$

        WorkspaceManager wsMgr = WorkspaceManager.getInstance(repository, uow);
        wsMgr.createDriver(uow, null, driverName);

        uow.commit();
        callback.await(3, TimeUnit.MINUTES);
    }

    /**
     * @return the drivers directly from the kEngine
     * @throws Exception if error occurs
     */
    public Driver[] getDrivers() throws Exception {
        Repository repository = this.kengine.getDefaultRepository();
        UnitOfWork uow = repository.createTransaction(Repository.SYSTEM_USER, "Find drivers", true, null); //$NON-NLS-1$
        WorkspaceManager mgr = WorkspaceManager.getInstance(repository, uow);
        Driver[] drivers = mgr.findDrivers(uow);
        uow.commit();

        return drivers;
    }
}
