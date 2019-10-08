/*
 * Copyright (c) 2002-2017, Mairie de Paris
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice
 *     and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright notice
 *     and the following disclaimer in the documentation and/or other materials
 *     provided with the distribution.
 *
 *  3. Neither the name of 'Mairie de Paris' nor 'Lutece' nor the names of its
 *     contributors may be used to endorse or promote products derived from
 *     this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 * License 1.0
 */
package fr.paris.lutece.plugins.grusupply.web.rs;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.paris.lutece.plugins.crmclient.util.CRMException;
import fr.paris.lutece.plugins.grubusiness.business.customer.Customer;
import fr.paris.lutece.plugins.grubusiness.business.demand.Demand;
import fr.paris.lutece.plugins.grubusiness.business.demand.DemandService;
import fr.paris.lutece.plugins.grubusiness.business.notification.Notification;
import fr.paris.lutece.plugins.grubusiness.service.notification.INotificationServiceProvider;
import fr.paris.lutece.plugins.grubusiness.service.notification.NotificationException;
import fr.paris.lutece.plugins.grusupply.constant.GruSupplyConstants;
import fr.paris.lutece.plugins.grusupply.service.CustomerProvider;
import fr.paris.lutece.plugins.grusupply.service.NotificationService;
import fr.paris.lutece.plugins.rest.service.RestConstants;
import fr.paris.lutece.portal.service.util.AppLogService;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.lang.StringUtils;

@Path( RestConstants.BASE_PATH + GruSupplyConstants.PLUGIN_NAME )
public class GRUSupplyRestService
{
    // Bean names
    private static final String BEAN_STORAGE_SERVICE = "grusupply.storageService";

    // Other constants
    private static final String STATUS_RECEIVED = "{ \"acknowledge\" : { \"status\": \"received\" } }";
    @Inject
    @Named( BEAN_STORAGE_SERVICE )
    private DemandService _demandService;

    /**
     * Web Service methode which permit to store the notification flow into a data store
     * 
     * @param strJson
     *            The JSON flow
     * @return The response
     */
    @POST
    @Path( "notification" )
    @Consumes( MediaType.APPLICATION_JSON )
    @Produces( MediaType.APPLICATION_JSON )
    public Response notification( String strJson )
    {
        try
        {
            // Format from JSON
            ObjectMapper mapper = new ObjectMapper( );
            mapper.configure( DeserializationFeature.UNWRAP_ROOT_VALUE, true );
            mapper.configure( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false );

            Notification notification = mapper.readValue( strJson, Notification.class );
            AppLogService.debug( "grusupply - Received strJson : " + strJson );

            Customer customerEncrypted = notification.getDemand( ).getCustomer( );
            Customer customerDecrypted = CustomerProvider.instance( ).decrypt( customerEncrypted, notification.getDemand( ) );

            if ( customerDecrypted != null && StringUtils.isNotEmpty( customerDecrypted.getConnectionId( ) )
                    && StringUtils.isEmpty( customerDecrypted.getId( ) ) )
            {
                Customer customerTmp = CustomerProvider.instance( ).get( customerDecrypted.getConnectionId( ), StringUtils.EMPTY );
                customerDecrypted.setId( customerTmp.getId( ) );
            }
            else if ( customerDecrypted == null )
            {
                customerDecrypted = new Customer( );
                customerDecrypted.setConnectionId( StringUtils.EMPTY );
                customerDecrypted.setId( StringUtils.EMPTY );
                notification.getDemand().setCustomer( customerDecrypted );
            }

            notification.getDemand( ).setCustomer( customerDecrypted );

            
           
            store( notification );

            // Notify user and crm if a bean NotificationService is instantiated
            NotificationService notificationService = NotificationService.instance( );

            if ( notificationService != null )
            {
                AppLogService.info( "GRUSUPPLY - Process Notification" + notification.getId( ) );

                NotificationService.instance().process( notification );                
                
            }
        }
        catch( JsonParseException ex )
        {
            return error( ex + " :" + ex.getMessage( ), ex );
        }
        catch( JsonMappingException ex )
        {
            return error( ex + " :" + ex.getMessage( ), ex );
        }
        catch( IOException ex )
        {
            return error( ex + " :" + ex.getMessage( ), ex );
        }
        catch( NullPointerException ex )
        {
            return error( ex + " :" + ex.getMessage( ), ex );
        } 
        catch (NotificationException ex) 
        {
            return error( ex + " :" + ex.getMessage( ), ex );
        }

        return Response.status( Response.Status.CREATED ).entity( STATUS_RECEIVED ).build( );
    }

    /**
     * Stores a notification and the associated demand
     * 
     * @param notification
     *            the notification to store
     */
    private void store( Notification notification )
    {
        Demand demand = _demandService.findByPrimaryKey( notification.getDemand( ).getId( ), notification.getDemand( ).getTypeId( ) );

        if ( demand == null )
        {
            demand = new Demand( );

            demand.setId( notification.getDemand( ).getId( ) );
            demand.setTypeId( notification.getDemand( ).getTypeId( ) );
            demand.setSubtypeId( notification.getDemand( ).getSubtypeId( ) );
            demand.setReference( notification.getDemand( ).getReference( ) );
            demand.setCreationDate( notification.getDate( ) );
            demand.setMaxSteps( notification.getDemand( ).getMaxSteps( ) );
            demand.setCurrentStep( notification.getDemand( ).getCurrentStep( ) );
            demand.setStatusId( notification.getDemand( ).getStatusId( ) );

            Customer customerDemand = new Customer( );
            customerDemand.setId( notification.getDemand( ).getCustomer( ).getId( ) );
            customerDemand.setConnectionId( notification.getDemand( ).getCustomer( ).getConnectionId( ) );
            demand.setCustomer( customerDemand );
            _demandService.create( demand );
        }
        else
        {
            demand.getCustomer( ).setId( notification.getDemand( ).getCustomer( ).getId( ) );
            demand.setCurrentStep( notification.getDemand( ).getCurrentStep( ) );

            // Demand opened to closed
            if ( ( demand.getStatusId( ) != fr.paris.lutece.plugins.grubusiness.business.demand.Demand.STATUS_CLOSED )
                    && ( notification.getDemand( ).getStatusId( ) == fr.paris.lutece.plugins.grubusiness.business.demand.Demand.STATUS_CLOSED ) )
            {
                demand.setStatusId( notification.getDemand( ).getStatusId( ) );
                demand.setClosureDate( notification.getDate( ) );
            }

            // Demand closed to opened
            if ( ( demand.getStatusId( ) == fr.paris.lutece.plugins.grubusiness.business.demand.Demand.STATUS_CLOSED )
                    && ( notification.getDemand( ).getStatusId( ) != fr.paris.lutece.plugins.grubusiness.business.demand.Demand.STATUS_CLOSED ) )
            {
                demand.setStatusId( notification.getDemand( ).getStatusId( ) );
                demand.setClosureDate( 0 );
            }

            _demandService.update( demand );
        }

        _demandService.create( notification );
    }

    /**
     * Build an error response
     * 
     * @param strMessage
     *            The error message
     * @param ex
     *            An exception
     * @return The response
     */
    private Response error( String strMessage, Throwable ex )
    {
        if ( ex != null )
        {
            AppLogService.error( strMessage, ex );
        }
        else
        {
            AppLogService.error( strMessage );
        }

        String strError = "{ \"status\": \"Error : " + strMessage + "\" }";

        return Response.status( Response.Status.INTERNAL_SERVER_ERROR ).entity( strError ).build( );
    }
}
