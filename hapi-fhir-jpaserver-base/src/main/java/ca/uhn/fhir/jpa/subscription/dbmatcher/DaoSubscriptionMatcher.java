package ca.uhn.fhir.jpa.subscription.dbmatcher;

/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2019 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.jpa.dao.DaoRegistry;
import ca.uhn.fhir.jpa.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.subscription.module.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.module.ResourceModifiedMessage;
import ca.uhn.fhir.jpa.subscription.module.matcher.ISubscriptionMatcher;
import ca.uhn.fhir.jpa.subscription.module.matcher.SubscriptionMatchResult;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import com.google.common.annotations.VisibleForTesting;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class DaoSubscriptionMatcher implements ISubscriptionMatcher {
	private static boolean ourForceDelayBeforeMatching;
	@Autowired
	DaoRegistry myDaoRegistry;
	@Autowired
	MatchUrlService myMatchUrlService;
	private Logger ourLog = LoggerFactory.getLogger(DaoSubscriptionMatcher.class);
	@Autowired
	private FhirContext myCtx;
	@Autowired
	private PlatformTransactionManager myTxManager;

	@Override
	public SubscriptionMatchResult match(CanonicalSubscription theSubscription, ResourceModifiedMessage theMsg) {
		IIdType id = theMsg.getId(myCtx);
		String resourceType = id.getResourceType();
		String resourceId = id.getIdPart();
		String criteria = theSubscription.getCriteriaString();

		// run the subscriptions query and look for matches, add the id as part of the criteria to avoid getting matches of previous resources rather than the recent resource
		criteria += "&_id=" + resourceType + "/" + resourceId;

		IBundleProvider results = performSearch(criteria);

		ourLog.debug("Subscription check found {} results for query: {}", results.size(), criteria);

		return SubscriptionMatchResult.fromBoolean(results.size() > 0);
	}

	/**
	 * Search based on a query criteria
	 */
	private IBundleProvider performSearch(String theCriteria) {
		IFhirResourceDao<?> subscriptionDao = myDaoRegistry.getSubscriptionDao();
		RuntimeResourceDefinition responseResourceDef = subscriptionDao.validateCriteriaAndReturnResourceDefinition(theCriteria);
		SearchParameterMap responseCriteriaUrl = myMatchUrlService.translateMatchUrl(theCriteria, responseResourceDef);

		IFhirResourceDao<? extends IBaseResource> responseDao = myDaoRegistry.getResourceDao(responseResourceDef.getImplementingClass());
		responseCriteriaUrl.setLoadSynchronousUpTo(1);

		// See the setter for this variable
		if (ourForceDelayBeforeMatching) {
			try {
				Thread.sleep(2000);
			} catch (InterruptedException theE) {
				theE.printStackTrace();
			}
		}

		TransactionTemplate txTemplate = new TransactionTemplate(myTxManager);
		txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
		return txTemplate.execute(t -> responseDao.search(responseCriteriaUrl));

	}

	/**
	 * Forces a delay before performing match queries against the database. This is only there
	 * to avoid derby nonsense. I can't figure out why this is needed, but if I remove it we
	 * get weird derby deadlocks.
	 */
	@VisibleForTesting
	public static void setForceDelayBeforeMatchingForUnitTest(boolean theForceDelayBeforeMatching) {
		ourForceDelayBeforeMatching = theForceDelayBeforeMatching;
	}
}
