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

package com.ning.billing.invoice;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.joda.time.DateTime;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

import com.google.inject.Inject;
import com.ning.billing.account.api.Account;
import com.ning.billing.account.api.AccountApiException;
import com.ning.billing.account.api.AccountUserApi;
import com.ning.billing.catalog.MockPlan;
import com.ning.billing.catalog.MockPlanPhase;
import com.ning.billing.catalog.api.BillingPeriod;
import com.ning.billing.catalog.api.Currency;
import com.ning.billing.catalog.api.Plan;
import com.ning.billing.catalog.api.PlanPhase;
import com.ning.billing.dbi.MysqlTestingHelper;
import com.ning.billing.entitlement.api.SubscriptionTransitionType;
import com.ning.billing.entitlement.api.billing.BillingModeType;
import com.ning.billing.entitlement.api.user.Subscription;
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.invoice.api.InvoiceApiException;
import com.ning.billing.invoice.api.InvoiceNotifier;
import com.ning.billing.invoice.dao.InvoiceDao;
import com.ning.billing.invoice.generator.InvoiceGenerator;
import com.ning.billing.invoice.notification.NextBillingDateNotifier;
import com.ning.billing.invoice.notification.NullInvoiceNotifier;
import com.ning.billing.invoice.tests.InvoicingTestBase;
import com.ning.billing.junction.api.BillingApi;
import com.ning.billing.junction.api.BillingEventSet;
import com.ning.billing.util.bus.BusService;
import com.ning.billing.util.bus.DefaultBusService;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.callcontext.CallOrigin;
import com.ning.billing.util.callcontext.DefaultCallContextFactory;
import com.ning.billing.util.callcontext.UserType;
import com.ning.billing.util.clock.Clock;
import com.ning.billing.util.globallocker.GlobalLocker;

@Guice(modules = {MockModule.class})
public class TestInvoiceDispatcher extends InvoicingTestBase {
    private final Logger log = LoggerFactory.getLogger(TestInvoiceDispatcher.class);

    @Inject
    private InvoiceGenerator generator;

    @Inject
    private InvoiceDao invoiceDao;

    @Inject
    private GlobalLocker locker;

    @Inject
    private MysqlTestingHelper helper;

    @Inject
    private NextBillingDateNotifier notifier;

    @Inject
    private BusService busService;

    @Inject
    private BillingApi billingApi;

    @Inject
    private Clock clock;

    private CallContext context;

    @BeforeSuite(groups = {"slow"})
    public void setup() throws Exception {
        notifier.initialize();
        notifier.start();

        context = new DefaultCallContextFactory(clock).createCallContext("Miracle Max", CallOrigin.TEST, UserType.TEST);

        busService.getBus().start();
    }

    @AfterClass(groups = {"slow"})
    public void tearDown() {
        try {
            ((DefaultBusService) busService).stopBus();
            notifier.stop();
        } catch (Exception e) {
            log.warn("Failed to tearDown test properly ", e);
        }
    }

    @Test(groups = {"slow"}, enabled = true)
    public void testDryRunInvoice() throws InvoiceApiException, AccountApiException {
        final UUID accountId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();

        final AccountUserApi accountUserApi = Mockito.mock(AccountUserApi.class);
        final Account account = Mockito.mock(Account.class);

        Mockito.when(accountUserApi.getAccountById(accountId)).thenReturn(account);
        Mockito.when(account.getCurrency()).thenReturn(Currency.USD);
        Mockito.when(account.getId()).thenReturn(accountId);
        Mockito.when(account.isNotifiedForInvoices()).thenReturn(true);

        final Subscription subscription = Mockito.mock(Subscription.class);
        Mockito.when(subscription.getId()).thenReturn(subscriptionId);
        Mockito.when(subscription.getBundleId()).thenReturn(new UUID(0L, 0L));
        final BillingEventSet events = new MockBillingEventSet();
        final Plan plan = MockPlan.createBicycleNoTrialEvergreen1USD();
        final PlanPhase planPhase = MockPlanPhase.create1USDMonthlyEvergreen();
        final DateTime effectiveDate = new DateTime().minusDays(1);
        final Currency currency = Currency.USD;
        final BigDecimal fixedPrice = null;
        events.add(createMockBillingEvent(account, subscription, effectiveDate, plan, planPhase,
                                          fixedPrice, BigDecimal.ONE, currency, BillingPeriod.MONTHLY, 1,
                                          BillingModeType.IN_ADVANCE, "", 1L, SubscriptionTransitionType.CREATE));

        Mockito.when(billingApi.getBillingEventsForAccountAndUpdateAccountBCD(accountId)).thenReturn(events);

        final DateTime target = new DateTime();

        final InvoiceNotifier invoiceNotifier = new NullInvoiceNotifier();
        final InvoiceDispatcher dispatcher = new InvoiceDispatcher(generator, accountUserApi, billingApi, invoiceDao,
                                                                   invoiceNotifier, locker, busService.getBus(), clock);

        Invoice invoice = dispatcher.processAccount(accountId, target, true, context);
        Assert.assertNotNull(invoice);

        List<Invoice> invoices = invoiceDao.getInvoicesByAccount(accountId);
        Assert.assertEquals(invoices.size(), 0);

        // Try it again to double check
        invoice = dispatcher.processAccount(accountId, target, true, context);
        Assert.assertNotNull(invoice);

        invoices = invoiceDao.getInvoicesByAccount(accountId);
        Assert.assertEquals(invoices.size(), 0);

        // This time no dry run
        invoice = dispatcher.processAccount(accountId, target, false, context);
        Assert.assertNotNull(invoice);

        invoices = invoiceDao.getInvoicesByAccount(accountId);
        Assert.assertEquals(invoices.size(), 1);

    }

    //MDW add a test to cover when the account auto-invoice-off tag is present
}
