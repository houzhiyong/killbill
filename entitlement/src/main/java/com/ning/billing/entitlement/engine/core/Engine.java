/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.entitlement.engine.core;

import java.util.List;
import java.util.UUID;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.ning.billing.catalog.api.ICatalog;
import com.ning.billing.catalog.api.ICatalogService;
import com.ning.billing.catalog.api.ICatalogUserApi;
import com.ning.billing.catalog.api.IPlan;
import com.ning.billing.config.IEntitlementConfig;

import com.ning.billing.entitlement.IEntitlementService;
import com.ning.billing.entitlement.alignment.IPlanAligner;
import com.ning.billing.entitlement.alignment.IPlanAligner.TimedPhase;
import com.ning.billing.entitlement.alignment.PlanAligner;
import com.ning.billing.entitlement.api.billing.BillingApi;
import com.ning.billing.entitlement.api.billing.IEntitlementBillingApi;
import com.ning.billing.entitlement.api.user.EntitlementUserApi;
import com.ning.billing.entitlement.api.user.IApiListener;
import com.ning.billing.entitlement.api.user.IEntitlementUserApi;
import com.ning.billing.entitlement.api.user.Subscription;
import com.ning.billing.entitlement.engine.dao.IEntitlementDao;
import com.ning.billing.entitlement.events.IEvent;
import com.ning.billing.entitlement.events.IEvent.EventType;
import com.ning.billing.entitlement.events.phase.IPhaseEvent;
import com.ning.billing.entitlement.events.phase.PhaseEvent;
import com.ning.billing.entitlement.events.user.IUserEvent;
import com.ning.billing.util.clock.IClock;

public class Engine implements IEventListener, IEntitlementService {

    private static final String ENTITLEMENT_SERVICE_NAME = "entitlement-service";

    private final static Logger log = LoggerFactory.getLogger(Engine.class);
    private static Engine instance = null;

    private final IClock clock;

    private final IEntitlementDao dao;
    private final IApiEventProcessor apiEventProcessor;
    private final ICatalogService catalogService;
    private final IPlanAligner planAligner;
    private final IEntitlementUserApi userApi;
    private final IEntitlementBillingApi billingApi;
    private List<IApiListener> observers;
    private ICatalog catalog;


    @Inject
    public Engine(IClock clock, IEntitlementDao dao, IApiEventProcessor apiEventProcessor, ICatalogService catalogService,
            IPlanAligner planAligner, IEntitlementConfig config) {
        super();
        this.clock = clock;
        this.dao = dao;
        this.apiEventProcessor = apiEventProcessor;
        this.planAligner = planAligner;
        this.catalogService = catalogService;
        this.observers = null;
        this.userApi = new EntitlementUserApi(this, clock, planAligner, dao);
        this.billingApi = new BillingApi(this, clock, dao);
        instance = this;

    }

    @Override
    public String getName() {
        return ENTITLEMENT_SERVICE_NAME;
    }


    @Override
    public void initialize() {
        this.catalog = catalogService.getCatalog();
        // STEPH yack
        ((PlanAligner) planAligner).init(catalog);

    }

    @Override
    public void start() {
        apiEventProcessor.startNotifications(this);
    }

    @Override
    public void stop() {
        apiEventProcessor.stopNotifications();
    }

    @Override
    public IEntitlementUserApi getUserApi(List<IApiListener> listeners) {
        userApi.initialize(listeners);
        return userApi;
    }

    @Override
    public IEntitlementBillingApi getBillingApi() {
        return billingApi;
    }


    public void registerApiObservers(List<IApiListener> observers) {
        this.observers = observers;
    }


    @Override
    public void processEventReady(IEvent event) {
        if (observers == null) {
            return;
        }
        Subscription subscription = (Subscription) dao.getSubscriptionFromId(event.getSubscriptionId());
        if (subscription == null) {
            log.warn("Failed to retrieve subscription for id %s", event.getSubscriptionId());
            return;
        }
        if (event.getType() == EventType.API_USER) {
            dispatchApiEvent((IUserEvent) event, subscription);
        } else {
            dispatchPhaseEvent((IPhaseEvent) event, subscription);
            insertNextPhaseEvent(subscription);
        }
    }

    private void dispatchApiEvent(IUserEvent event, Subscription subscription) {
        for (IApiListener listener : observers) {
            switch(event.getEventType()) {
            case CREATE:
                listener.subscriptionCreated(subscription.getLatestTranstion());
                break;
            case CHANGE:
                listener.subscriptionChanged(subscription.getLatestTranstion());
                break;
            case CANCEL:
                listener.subscriptionCancelled(subscription.getLatestTranstion());
                break;
            default:
                break;
            }
        }
    }

    private void dispatchPhaseEvent(IPhaseEvent event, Subscription subscription) {
        for (IApiListener listener : observers) {
            listener.subscriptionPhaseChanged(subscription.getLatestTranstion());
        }
    }

    private void insertNextPhaseEvent(Subscription subscription) {

        DateTime now = clock.getUTCNow();

        TimedPhase nextTimedPhase = planAligner.getNextTimedPhase(subscription, subscription.getCurrentPlan(), now, subscription.getCurrentPlanStart());
        IPhaseEvent nextPhaseEvent = PhaseEvent.getNextPhaseEvent(nextTimedPhase, subscription, now);
        if (nextPhaseEvent != null) {
            // STEPH Harden since event could be processed twice
            dao.createNextPhaseEvent(subscription.getId(), nextPhaseEvent);
        }
    }


    //
    // STEPH would be nice to have those go away..
    //

    // For non Guice classes
    public synchronized static Engine getInstance() {
        return instance;
    }

    public IClock getClock() {
        return clock;
    }

    public ICatalog getCatalog() {
        return catalog;
    }

    public IEntitlementDao getDao() {
        return dao;
    }

    public IPlanAligner getPlanAligner() {
        return planAligner;
    }

}
