/*************************************************************************
 *                                                                       *
 *  EJBCA Community: The OpenSource Certificate Authority                *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/
package org.ejbca.ra;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.ejb.EJB;
import javax.faces.bean.ManagedBean;
import javax.faces.bean.ManagedProperty;
import javax.faces.bean.ViewScoped;
import javax.faces.context.FacesContext;
import javax.servlet.http.HttpServletRequest;

import org.apache.log4j.Logger;
import org.cesecore.authorization.AuthorizationDeniedException;
import org.ejbca.core.model.era.RaApprovalRequestInfo;
import org.ejbca.core.model.era.RaApprovalResponseRequest;
import org.ejbca.core.model.era.RaApprovalResponseRequest.Action;
import org.ejbca.core.model.era.RaMasterApiProxyBeanLocal;

/**
 * Backing bean for Manage Request page (for individual requests).
 *  
 * @see RaManageRequestsBean
 * @version $Id$
 */
@ManagedBean
@ViewScoped
public class RaManageRequestBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(RaManageRequestBean.class);
    
    public class ApprovalDataRow {
        private final String nameText;
        private final String valueText;
        
        public ApprovalDataRow(final String name, final String value) {
            nameText = name; // TODO
            valueText = value; // TODO some values might need translation
        }
        
        public String getNameText() { return nameText; }
        public String getValueText() { return valueText; }
    }
    
    @EJB
    private RaMasterApiProxyBeanLocal raMasterApiProxyBean;

    @ManagedProperty(value="#{raAuthenticationBean}")
    private RaAuthenticationBean raAuthenticationBean;
    public void setRaAuthenticationBean(final RaAuthenticationBean raAuthenticationBean) { this.raAuthenticationBean = raAuthenticationBean; }

    @ManagedProperty(value="#{raLocaleBean}")
    private RaLocaleBean raLocaleBean;
    public void setRaLocaleBean(final RaLocaleBean raLocaleBean) { this.raLocaleBean = raLocaleBean; }

    
    private ApprovalRequestGUIInfo requestInfo;
    
    private void initializeRequestInfo() {
        if (requestInfo == null) {
            final String idHttpParam = ((HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest())
                    .getParameter("id");
            final int id = Integer.parseInt(idHttpParam);
            final RaApprovalRequestInfo request = raMasterApiProxyBean.getApprovalRequest(raAuthenticationBean.getAuthenticationToken(), id);
            requestInfo = new ApprovalRequestGUIInfo(request, raLocaleBean);
            if (requestInfo == null) {
                throw new IllegalStateException("Request does not exist");
            }
        }
    }
    
    public ApprovalRequestGUIInfo getRequest() {
        initializeRequestInfo();
        return requestInfo;
    }
    
    public String getPageTitle() {
        return raLocaleBean.getMessage("view_request_page_title", getRequest().getDisplayName());
    }
    
    public List<ApprovalDataRow> getRequestData() {
        // TODO
        return new ArrayList<>();
    }
    
    public boolean isHasNextStep() {
        initializeRequestInfo();
        return requestInfo != null && requestInfo.getNextStep() != null;
    }
    
    public List<ApprovalRequestGUIInfo.StepControl> getNextStepControls() {
        initializeRequestInfo();
        if (requestInfo != null && requestInfo.getNextStep() != null) {
            return getRequest().getNextStep().getControls();
        } else {
            return null;
        }
    }
    
    private RaApprovalResponseRequest buildApprovalResponseRequest(final Action action) {
        final List<ApprovalRequestGUIInfo.StepControl> controls = getNextStepControls();
        final int id = getRequest().request.getId();
        final int stepId = getRequest().getNextStep().getStepId();
        final RaApprovalResponseRequest approval = new RaApprovalResponseRequest(id, stepId, "", action); // TODO comment field. should it be here for partitioned approvals?
        for (final ApprovalRequestGUIInfo.StepControl control : controls) {
            approval.addMetadata(control.getMetadataId(), control.getOptionValue(), control.getOptionNote());
        }
        return approval;
    }
    
    public void approve() throws AuthorizationDeniedException {
        final RaApprovalResponseRequest responseReq = buildApprovalResponseRequest(Action.APPROVE);
        if (raMasterApiProxyBean.addRequestResponse(raAuthenticationBean.getAuthenticationToken(), responseReq)) {
            // TODO add success message
        } else {
            // TODO this means that there was no backend available. add failure message
        }
    }
    
    public void reject() throws AuthorizationDeniedException {
        final RaApprovalResponseRequest responseReq = buildApprovalResponseRequest(Action.REJECT);
        if (raMasterApiProxyBean.addRequestResponse(raAuthenticationBean.getAuthenticationToken(), responseReq)) {
            // TODO add success message
        } else {
            // TODO this means that there was no backend available. add failure message
        }
    }
    
}
