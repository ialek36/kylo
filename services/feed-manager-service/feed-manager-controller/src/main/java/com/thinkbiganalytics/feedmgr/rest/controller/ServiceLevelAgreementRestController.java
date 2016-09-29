package com.thinkbiganalytics.feedmgr.rest.controller;

import com.google.common.collect.Lists;
import com.thinkbiganalytics.feedmgr.InvalidOperationException;
import com.thinkbiganalytics.feedmgr.sla.ServiceLevelAgreementGroup;
import com.thinkbiganalytics.feedmgr.sla.ServiceLevelAgreementMetricTransformerHelper;
import com.thinkbiganalytics.feedmgr.sla.ServiceLevelAgreementModelTransform;
import com.thinkbiganalytics.feedmgr.sla.ServiceLevelAgreementService;
import com.thinkbiganalytics.metadata.api.OperationalMetadataAccess;
import com.thinkbiganalytics.metadata.modeshape.JcrMetadataAccess;
import com.thinkbiganalytics.metadata.rest.model.sla.FeedServiceLevelAgreement;
import com.thinkbiganalytics.metadata.rest.model.sla.ServiceLevelAgreement;
import com.thinkbiganalytics.metadata.rest.model.sla.ServiceLevelAssessment;
import com.thinkbiganalytics.metadata.sla.api.ServiceLevelAgreementActionValidation;
import com.thinkbiganalytics.metadata.sla.spi.ServiceLevelAgreementProvider;
import com.thinkbiganalytics.metadata.sla.spi.ServiceLevelAssessor;
import com.thinkbiganalytics.rest.model.beanvalidation.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

@Api(value = "Service Level Agreements", produces = "application/json")
@Path("/v1/feedmgr/sla")
@Component
public class ServiceLevelAgreementRestController {

    @Inject
    ServiceLevelAgreementService serviceLevelAgreementService;


    private static final Logger log = LoggerFactory.getLogger(ServiceLevelAgreementRestController.class);

    @Inject
    private ServiceLevelAgreementProvider provider;

    @Inject
    private JcrMetadataAccess metadata;

    @Inject
    private OperationalMetadataAccess operationalMetadataAccess;

    @Inject
    ServiceLevelAssessor assessor;

    @GET
    @Path("/available-metrics")
    @Produces({MediaType.APPLICATION_JSON})
    public Response getAvailableSLAMetrics() {
        return Response.ok(serviceLevelAgreementService.discoverSlaMetrics()).build();
    }

    @GET
    @Path("/available-responders")
    @Produces({MediaType.APPLICATION_JSON})
    public Response getAvailableSLAResponders() {
        return Response.ok(serviceLevelAgreementService.discoverActionConfigurations()).build();
    }

    /**
     * Save the General SLA coming in from the UI that is not related to a Feed
     */
    @POST
    @Produces({MediaType.APPLICATION_JSON})
    @ApiOperation(value = "saveSla")
    public Response saveSla(ServiceLevelAgreementGroup sla) {
        ServiceLevelAgreement serviceLevelAgreement = serviceLevelAgreementService.saveAndScheduleSla(sla);
        ServiceLevelAgreementMetricTransformerHelper helper = new ServiceLevelAgreementMetricTransformerHelper();
        ServiceLevelAgreementGroup serviceLevelAgreementGroup = helper.toServiceLevelAgreementGroup(serviceLevelAgreement);
        return Response.ok(serviceLevelAgreementGroup).build();
    }

    /**
     * Save an SLA and attach the ref to the Feed
     */
    @POST
    @Path("/feed/{feedId}")
    @Produces({MediaType.APPLICATION_JSON})
    @ApiOperation(value = "saveAndScheduleFeedSla")
    public Response saveAndScheduleFeedSla(@UUID @PathParam("feedId") String feedId, ServiceLevelAgreementGroup sla) {
        ServiceLevelAgreement serviceLevelAgreement = serviceLevelAgreementService.saveAndScheduleFeedSla(sla, feedId);
        ServiceLevelAgreementMetricTransformerHelper helper = new ServiceLevelAgreementMetricTransformerHelper();
        ServiceLevelAgreementGroup serviceLevelAgreementGroup = helper.toServiceLevelAgreementGroup(serviceLevelAgreement);
        return Response.ok(serviceLevelAgreementGroup).build();
    }


    /**
     * Delete an SLA
     */
    @DELETE
    @Path("/{slaId}")
    @Produces({MediaType.APPLICATION_JSON})
    @ApiOperation(value = "deleteSla")
    public Response deleteSla(@UUID @PathParam("slaId") String slaId) throws InvalidOperationException {
        serviceLevelAgreementService.removeAndUnscheduleAgreement(slaId);

        return Response.ok().build();
    }


    @GET
    @Path("/feed")
    @Produces({MediaType.APPLICATION_JSON})
    @ApiOperation(value = "Get All Feed Related SLAs")
    public Response getAllSlas() {
        List<FeedServiceLevelAgreement> agreementList = serviceLevelAgreementService.getServiceLevelAgreements();
        if (agreementList == null) {
            agreementList = new ArrayList<>();
        }
        return Response.ok(agreementList).build();
    }

    @GET
    @Path("/{slaId}/form-object")
    @Produces({MediaType.APPLICATION_JSON})
    @ApiOperation(value = "getSlaAsForm")
    public Response getSlaAsForm(@UUID @PathParam("slaId") String slaId) {
        ServiceLevelAgreementGroup agreement = serviceLevelAgreementService.getServiceLevelAgreementAsFormObject(slaId);

        return Response.ok(agreement).build();
    }


    @GET
    @Path("/action/validate")
    @Produces({MediaType.APPLICATION_JSON})
    @ApiOperation(value = "validateActionConfiguration")
    public Response validateAction(@QueryParam("actionConfigClass") String actionConfigClass) {

        List<ServiceLevelAgreementActionValidation> validation = serviceLevelAgreementService.validateAction(actionConfigClass);
        return Response.ok(validation).build();
    }

// TODO: this method was conflicting with saveSla()
//    @POST
//    @Consumes(MediaType.APPLICATION_JSON)
//    @Produces(MediaType.APPLICATION_JSON)
//    public ServiceLevelAgreement createAgreement(ServiceLevelAgreement agreement) {
//        log.debug("POST Create SLA {}", agreement);
//
//        return this.metadata.commit(() -> {
//            com.thinkbiganalytics.metadata.sla.api.ServiceLevelAgreement domainSla
//                = ServiceLevelAgreementModelTransform.generateDomain(agreement, this.provider);
//
//            return ServiceLevelAgreementModelTransform.DOMAIN_TO_SLA.apply(domainSla);
//        });
//    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<ServiceLevelAgreement> getAgreements() {
        log.debug("GET all SLA's");

        return this.metadata.commit(() -> {
            List<? extends com.thinkbiganalytics.metadata.sla.api.ServiceLevelAgreement> list = this.provider.getAgreements();

            List<ServiceLevelAgreement> transformedList = Lists.newArrayList(Lists.transform(list, ServiceLevelAgreementModelTransform.DOMAIN_TO_SLA));
            return transformedList;

        });
    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public ServiceLevelAgreement getAgreement(@PathParam("id") String idValue) {
        log.debug("GET SLA by ID: {}", idValue);

        return this.metadata.commit(() -> {
            com.thinkbiganalytics.metadata.sla.api.ServiceLevelAgreement.ID id = this.provider.resolve(idValue);
            com.thinkbiganalytics.metadata.sla.api.ServiceLevelAgreement sla = this.provider.getAgreement(id);

            if (sla != null) {
                return ServiceLevelAgreementModelTransform.DOMAIN_TO_SLA.apply(sla);
            } else {
                throw new WebApplicationException("No SLA with the given ID was found", Response.Status.NOT_FOUND);
            }
        });
    }

    @GET
    @Path("{id}/assessment")
    @Produces(MediaType.APPLICATION_JSON)
    public ServiceLevelAssessment assessAgreement(@PathParam("id") String idValue) {
        log.debug("GET SLA by ID: {}", idValue);

        return this.metadata.read(() -> {
            com.thinkbiganalytics.metadata.sla.api.ServiceLevelAgreement.ID id = this.provider.resolve(idValue);
            com.thinkbiganalytics.metadata.sla.api.ServiceLevelAgreement sla = this.provider.getAgreement(id);
            com.thinkbiganalytics.metadata.sla.api.ServiceLevelAssessment assessment = this.assessor.assess(sla);

            return ServiceLevelAgreementModelTransform.DOMAIN_TO_SLA_ASSMT.apply(assessment);
        });
    }






}
