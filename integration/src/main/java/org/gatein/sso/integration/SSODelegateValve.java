/*
 * JBoss, a division of Red Hat
 * Copyright 2012, Red Hat Middleware, LLC, and individual
 * contributors as indicated by the @authors tag. See the
 * copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.gatein.sso.integration;

import org.apache.catalina.Contained;
import org.apache.catalina.Container;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.gatein.common.logging.Logger;
import org.gatein.common.logging.LoggerFactory;
import org.jboss.servlet.http.HttpEvent;

import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.servlet.ServletException;
import java.io.IOException;

/**
 * Delegates work to another valve configured through option 'delegateValveClassName'. It's possible to disable
 * delegation by boolean parameter 'ssoDelegationEnabled'.
 *
 * Actually delegation will be enabled only for SSO scenario, which require integration with Tomcat valves (SAML, SPNEGO)
 *
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class SSODelegateValve implements Valve, Contained, MBeanRegistration
{
   private static final Logger log = LoggerFactory.getLogger(SSODelegateValve.class);

   // Injected by container
   // If true, then we are in SSO and we have delegate valve where we should resend all method calls
   // If false, we are not in SSO and we don't have delegate
   private boolean delegationEnabled;

   // Injected by container
   private String delegateValveClassName;

   // Delegate valve is not null only if we are in SSO mode and delegation is enabled
   // Delegate will be either SAML or SPNEGO valve
   private Valve delegate;

   // This is not null only if delegation is disabled
   private Valve next;

   // Catalina context
   private Container context;

   public void setDelegateValveClassName(String delegateValve)
   {
      // We need to replace system properties by ourselves, so we need to define in configuration like #{prop} instead of ${prop}
      delegateValve = delegateValve.replace("#", "$");
      delegateValve = SSOUtils.substituteSystemProperty(delegateValve);
      this.delegateValveClassName = delegateValve;
      log.debug("delegateValveClassName: " + delegateValveClassName);
   }

   public void setSsoDelegationEnabled(String enabled)
   {
      // We need to replace system properties by ourselves, so we need to define in configuration like #{prop} instead of ${prop}
      enabled = enabled.replace("#", "$");
      enabled = SSOUtils.substituteSystemProperty(enabled);
      this.delegationEnabled = Boolean.parseBoolean(enabled);
      log.debug("ssoDelegationEnabled: " + delegationEnabled);
   }

   public String getInfo()
   {
      if (delegationEnabled)
      {
         Valve delegate = getOrLoadDelegate(delegateValveClassName);
         return delegate.getInfo();
      }
      else
      {
         return "SSODelegateValve with disabled delegation";
      }
   }

   public Valve getNext()
   {
      if (delegationEnabled)
      {
         Valve delegate = getOrLoadDelegate(delegateValveClassName);
         return delegate.getNext();
      }
      else
      {
         return next;
      }
   }

   public void setNext(Valve valve)
   {
      if (delegationEnabled)
      {
         Valve delegate = getOrLoadDelegate(delegateValveClassName);
         delegate.setNext(valve);
      }
      else
      {
         this.next = valve;
      }

   }

   public void backgroundProcess()
   {
      if (delegationEnabled)
      {
         Valve delegate = getOrLoadDelegate(delegateValveClassName);
         delegate.backgroundProcess();
      }
   }

   public void invoke(Request request, Response response) throws IOException, ServletException
   {
      if (delegationEnabled)
      {
         Valve delegate = getOrLoadDelegate(delegateValveClassName);
         delegate.invoke(request, response);
      }
      else
      {
         next.invoke(request, response);
      }
   }

   public void event(Request request, Response response, HttpEvent event) throws IOException, ServletException
   {
      if (delegationEnabled)
      {
         Valve delegate = getOrLoadDelegate(delegateValveClassName);
         delegate.event(request, response, event);
      }
      else
      {
         next.event(request, response, event);
      }
   }

   public Container getContainer()
   {
      return context;
   }

   public void setContainer(Container container)
   {
      if (delegationEnabled)
      {
         Valve delegate = getOrLoadDelegate(delegateValveClassName);
         if (delegate instanceof Contained)
         {
            ((Contained) delegate).setContainer(container);
         }
      }
      this.context = container;

   }

   public ObjectName preRegister(MBeanServer server, ObjectName name) throws Exception
   {
      if (delegationEnabled)
      {
         Valve delegate = getOrLoadDelegate(delegateValveClassName);
         if (delegate instanceof MBeanRegistration)
         {
            return ((MBeanRegistration) delegate).preRegister(server,name);
         }
      }
      return name;
   }

   public void postRegister(Boolean registrationDone)
   {
      if (delegationEnabled)
      {
         Valve delegate = getOrLoadDelegate(delegateValveClassName);
         if (delegate instanceof MBeanRegistration)
         {
            ((MBeanRegistration) delegate).postRegister(registrationDone);
         }
      }
   }

   public void preDeregister() throws Exception
   {
      if (delegationEnabled)
      {
         Valve delegate = getOrLoadDelegate(delegateValveClassName);
         if (delegate instanceof MBeanRegistration)
         {
            ((MBeanRegistration) delegate).preDeregister();
         }
      }
   }

   public void postDeregister()
   {
      if (delegationEnabled)
      {
         Valve delegate = getOrLoadDelegate(delegateValveClassName);
         if (delegate instanceof MBeanRegistration)
         {
            ((MBeanRegistration) delegate).postDeregister();
         }
      }
   }

   private Valve getOrLoadDelegate(String className)
   {
      if (delegate == null)
      {
         if (className == null)
         {
            throw new IllegalStateException("Delegate className is null in SSODelegateValve");
         }

         Class<Valve> delegateClass = (Class<Valve>)SSOUtils.loadClass(className);
         try
         {
            this.delegate = delegateClass.newInstance();
            log.info("Delegating valve created successfuly: " + delegate);
         }
         catch (Exception e)
         {
            throw new RuntimeException("Can't instantiate " + delegateClass, e);
         }
      }

      return delegate;
   }
}
