/*************************************************************************
 *                                                                       *
 *  CESeCore: CE Security Core                                           *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/
package org.cesecore.keys.token;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;

import org.apache.log4j.Logger;
import org.bouncycastle.util.encoders.Hex;
import org.cesecore.audit.enums.EventStatus;
import org.cesecore.audit.enums.EventTypes;
import org.cesecore.audit.enums.ModuleTypes;
import org.cesecore.audit.enums.ServiceTypes;
import org.cesecore.audit.log.AuditRecordStorageException;
import org.cesecore.audit.log.SecurityEventsLoggerSessionLocal;
import org.cesecore.authentication.tokens.AuthenticationToken;
import org.cesecore.authorization.AuthorizationDeniedException;
import org.cesecore.authorization.control.AccessControlSessionLocal;
import org.cesecore.authorization.control.CryptoTokenRules;
import org.cesecore.certificates.util.AlgorithmConstants;
import org.cesecore.certificates.util.AlgorithmTools;
import org.cesecore.internal.InternalResources;
import org.cesecore.jndi.JndiConstants;
import org.cesecore.keys.token.p11.exception.NoSuchSlotException;
import org.cesecore.keys.util.KeyTools;
import org.cesecore.util.CryptoProviderTools;

/**
 * @see CryptoTokenManagementSession
 * @version $Id$
 */
@Stateless(mappedName = JndiConstants.APP_JNDI_PREFIX + "CryptoTokenManagementSessionRemote")
@TransactionAttribute(TransactionAttributeType.REQUIRED)
public class CryptoTokenManagementSessionBean implements CryptoTokenManagementSessionLocal, CryptoTokenManagementSessionRemote {
    
    private static final Logger log = Logger.getLogger(CryptoTokenManagementSessionBean.class);
    /** Internal localization of logs and errors */
    private static final InternalResources INTRES = InternalResources.getInstance();
    private static final Random rnd = new SecureRandom();

    @EJB
    private AccessControlSessionLocal accessControlSessionSession;
    @EJB
    private SecurityEventsLoggerSessionLocal securityEventsLoggerSession;
    @EJB
    private CryptoTokenSessionLocal cryptoTokenSession;

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    @Override
    public List<Integer> getCryptoTokenIds(final AuthenticationToken authenticationToken) {
        final List<Integer> allCryptoTokenIds = cryptoTokenSession.getCryptoTokenIds();
        final List<Integer> auhtorizedCryptoTokenIds = new ArrayList<Integer>();
        for (final Integer current : allCryptoTokenIds) {
            if (accessControlSessionSession.isAuthorizedNoLogging(authenticationToken, CryptoTokenRules.VIEW.resource()+"/"+current.toString())) {
                auhtorizedCryptoTokenIds.add(current);
            }
        }
        return auhtorizedCryptoTokenIds;
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    @Override
    public CryptoToken getCryptoToken(final int cryptoTokenId) {
        return cryptoTokenSession.getCryptoToken(cryptoTokenId);
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    @Override
    public CryptoTokenInfo getCryptoTokenInfo(final AuthenticationToken authenticationToken, final int cryptoTokenId) throws AuthorizationDeniedException {
        if (!accessControlSessionSession.isAuthorized(authenticationToken, CryptoTokenRules.VIEW.resource()+"/"+cryptoTokenId)) {
            final String msg = INTRES.getLocalizedMessage("authorization.notuathorizedtoresource", CryptoTokenRules.VIEW.resource(), authenticationToken.toString());
            throw new AuthorizationDeniedException(msg);
        }
        return getCryptoTokenInfo(cryptoTokenId);
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    @Override
    public List<CryptoTokenInfo> getCryptoTokenInfos(final AuthenticationToken authenticationToken) {
        final List<CryptoTokenInfo> cryptoTokenInfos = new ArrayList<CryptoTokenInfo>();
        for (final Integer cryptoTokenId : getCryptoTokenIds(authenticationToken)) {
            cryptoTokenInfos.add(getCryptoTokenInfo(cryptoTokenId));
        }
        return cryptoTokenInfos;
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    @Override
    public CryptoTokenInfo getCryptoTokenInfo(final int cryptoTokenId) {
        final CryptoToken cryptoToken = cryptoTokenSession.getCryptoToken(cryptoTokenId);
        if (cryptoToken==null) {
            return null;
        }
        final boolean isActive = cryptoToken.getTokenStatus() == CryptoToken.STATUS_ACTIVE;
        final Properties cryptoTokenProperties = cryptoToken.getProperties();
        final boolean autoActivation = BaseCryptoToken.getAutoActivatePin(cryptoTokenProperties) != null;
        return new CryptoTokenInfo(cryptoTokenId, cryptoToken.getTokenName(), isActive, autoActivation, cryptoToken.getClass(), cryptoTokenProperties);
    }

    @Override
    public void createCryptoToken(final AuthenticationToken authenticationToken, final String tokenName, final Integer cryptoTokenId,
            final String className, final Properties properties, final byte[] data, final char[] authenticationCode)
            throws AuthorizationDeniedException, CryptoTokenNameInUseException, CryptoTokenOfflineException,
            CryptoTokenAuthenticationFailedException, NoSuchSlotException {
        if (log.isTraceEnabled()) {
            log.trace(">createCryptoToken: " + tokenName + ", " + className);
        }
        assertAuthorizedToModifyCryptoTokens(authenticationToken);
        if (CryptoTokenFactory.instance().getAvailableCryptoToken(className) == null) {
            throw new CryptoTokenClassNotFoundException("Invalid token class name: " + className);
        }

        // Note: if data is null, a new empty keystore will be created
        final CryptoToken cryptoToken = CryptoTokenFactory.createCryptoToken(className, properties, data, cryptoTokenId.intValue(), tokenName);
        if (authenticationCode != null) {
            cryptoToken.activate(authenticationCode);
        }
        final Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("msg", "Created CryptoToken with id " + cryptoTokenId);
        details.put("name", cryptoToken.getTokenName());
        details.put("encProviderName", cryptoToken.getEncProviderName());
        details.put("signProviderName", cryptoToken.getSignProviderName());
        putDelta(new Properties(), cryptoToken.getProperties(), details);
        cryptoTokenSession.mergeCryptoToken(cryptoToken);
        securityEventsLoggerSession.log(EventTypes.CRYPTOTOKEN_CREATE, EventStatus.SUCCESS, ModuleTypes.CRYPTOTOKEN, ServiceTypes.CORE,
                authenticationToken.toString(), String.valueOf(cryptoTokenId), null, null, details);
        if (log.isTraceEnabled()) {
            log.trace("<createCryptoToken: " + tokenName + ", " + className);
        }
    }
    
    @Override
    public int createCryptoToken(final AuthenticationToken authenticationToken, final String tokenName, final String className,
            final Properties properties, final byte[] data, final char[] authenticationCode) throws AuthorizationDeniedException,
            CryptoTokenOfflineException, CryptoTokenAuthenticationFailedException, CryptoTokenNameInUseException, NoSuchSlotException,
            AuditRecordStorageException {
        final List<Integer> allCryptoTokenIds = cryptoTokenSession.getCryptoTokenIds();
        Integer cryptoTokenId = null;
        for (int i = 0; i < 100; i++) {
            final int current = Integer.valueOf(rnd.nextInt());
            if (!allCryptoTokenIds.contains(current)) {
                cryptoTokenId = current;
                break;
            }
        }
        if (cryptoTokenId == null) {
            throw new RuntimeException("Failed to allocate a new cryptoTokenId.");
        }
        createCryptoToken(authenticationToken, tokenName, cryptoTokenId, className, properties, data, authenticationCode);
        return cryptoTokenId.intValue();
    }
    
    /**
     * Asserts if an authentication token is authorized to modify crypto tokens
     * 
     * @param authenticationToken the authentication token to check
     * @throws AuthorizationDeniedException thrown if authorization was denied.
     */
    private void assertAuthorizedToModifyCryptoTokens(AuthenticationToken authenticationToken) throws AuthorizationDeniedException  {
        if (!accessControlSessionSession.isAuthorized(authenticationToken, CryptoTokenRules.MODIFY_CRYPTOTOKEN.resource())) {
            final String msg = INTRES.getLocalizedMessage("authorization.notuathorizedtoresource", CryptoTokenRules.MODIFY_CRYPTOTOKEN.resource(), authenticationToken.toString());
            throw new AuthorizationDeniedException(msg);
        }
    }
    
    @Override
    public void saveCryptoToken(AuthenticationToken authenticationToken, int cryptoTokenId, String tokenName, Properties properties,
            char[] authenticationCode) throws AuthorizationDeniedException, CryptoTokenOfflineException, CryptoTokenAuthenticationFailedException,
            CryptoTokenNameInUseException, NoSuchSlotException {
        if (log.isTraceEnabled()) {
            log.trace(">saveCryptoToken: "+tokenName+", "+cryptoTokenId);
        }
        // Note that an admin that is authorized to modify a token could gain access to another HSM slot etc..
        if (!accessControlSessionSession.isAuthorized(authenticationToken, CryptoTokenRules.MODIFY_CRYPTOTOKEN.resource())) {
            final String msg = INTRES.getLocalizedMessage("authorization.notuathorizedtoresource", CryptoTokenRules.MODIFY_CRYPTOTOKEN.resource(), authenticationToken.toString());
            throw new AuthorizationDeniedException(msg);
        }
        final CryptoToken currentCryptoToken = cryptoTokenSession.getCryptoToken(cryptoTokenId);
        final String className = currentCryptoToken.getClass().getName();
        final byte[] tokendata = currentCryptoToken.getTokenData();
        // Handle presence of auto-activation indicators
        boolean keepAutoActivateIfPresent = Boolean.valueOf(String.valueOf(properties.get(CryptoTokenManagementSession.KEEP_AUTO_ACTIVATION_PIN)));
        properties.remove(CryptoTokenManagementSession.KEEP_AUTO_ACTIVATION_PIN);
        final String newPin = BaseCryptoToken.getAutoActivatePin(properties);
        if (newPin != null) {
            BaseCryptoToken.setAutoActivatePin(properties, newPin, true);
            authenticationCode = newPin.toCharArray();
        } else if (keepAutoActivateIfPresent) {
            final String currentPin = BaseCryptoToken.getAutoActivatePin(currentCryptoToken.getProperties());
            if (currentPin!=null) {
                BaseCryptoToken.setAutoActivatePin(properties, currentPin, true);
                authenticationCode = null;  // We have an auto-activation pin and it didn't change;
            }
        } else if (authenticationCode==null || authenticationCode.length==0) {
            // Check if the token was auto-activated before. it is now manually activated, so use the auto-activation code one last time
            final String currentPin = BaseCryptoToken.getAutoActivatePin(currentCryptoToken.getProperties());
            if (currentPin!=null) {
                authenticationCode = currentPin.toCharArray();
            }
        }
        // TODO: If the current token is active we would like to dig out the code used to activate it and activate the new one as well..
        // For SoftCryptoTokens, a new secret means that we should change it and it can only be done if the token is active
        final CryptoToken newCryptoToken = CryptoTokenFactory.createCryptoToken(className, properties, tokendata, cryptoTokenId, tokenName);
        // If a new authenticationCode is provided we should verify it before we go ahead and merge
        if (authenticationCode != null && authenticationCode.length>0) {
            newCryptoToken.deactivate();
            newCryptoToken.activate(authenticationCode);
        }
        final Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("msg", "Modified CryptoToken with id " + cryptoTokenId);
        putDelta("name", currentCryptoToken.getTokenName(), newCryptoToken.getTokenName(), details);
        putDelta("encProviderName", currentCryptoToken.getEncProviderName(), newCryptoToken.getEncProviderName(), details);
        putDelta("signProviderName", currentCryptoToken.getSignProviderName(), newCryptoToken.getSignProviderName(), details);
        putDelta(currentCryptoToken.getProperties(), newCryptoToken.getProperties(), details);
        cryptoTokenSession.mergeCryptoToken(newCryptoToken);
        securityEventsLoggerSession.log(EventTypes.CRYPTOTOKEN_EDIT, EventStatus.SUCCESS, ModuleTypes.CRYPTOTOKEN, ServiceTypes.CORE, authenticationToken.toString(), String.valueOf(cryptoTokenId), null, null, details);
        if (log.isTraceEnabled()) {
            log.trace("<saveCryptoToken: "+tokenName+", "+cryptoTokenId);
        }
    }

    // Only removes reference
    @Override
    public void deleteCryptoToken(final AuthenticationToken authenticationToken, final int cryptoTokenId) throws AuthorizationDeniedException {
        if (!accessControlSessionSession.isAuthorized(authenticationToken, CryptoTokenRules.DELETE_CRYPTOTOKEN.resource()+"/"+cryptoTokenId)) {
            throw new AuthorizationDeniedException();
        }
        if (cryptoTokenSession.removeCryptoToken(cryptoTokenId)) {
            securityEventsLoggerSession.log(EventTypes.CRYPTOTOKEN_DELETE, EventStatus.SUCCESS, ModuleTypes.CRYPTOTOKEN, ServiceTypes.CORE, authenticationToken.toString(), String.valueOf(cryptoTokenId), null, null,
                    "Deleted CryptoToken with id " + cryptoTokenId);
        } else if (log.isDebugEnabled()) {
            log.debug("Crypto token with id "+cryptoTokenId+" does not exist and can not be deleted.");
        }
    }
    
    
    @Override
    public boolean isCryptoTokenStatusActive(AuthenticationToken authenticationToken, int cryptoTokenId) throws AuthorizationDeniedException {
        assertAuthorization(authenticationToken, cryptoTokenId, CryptoTokenRules.VIEW.resource() + "/" + cryptoTokenId);
        return isCryptoTokenStatusActive(cryptoTokenId);
    }
    
    @Override
    public boolean isCryptoTokenStatusActive(int cryptoTokenId)  {
        final CryptoToken cryptoToken = getCryptoTokenAndAssertExistence(cryptoTokenId);
        return cryptoToken.getTokenStatus() == CryptoToken.STATUS_ACTIVE;
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRED) 
    @Override
    public synchronized void adhocUpgradeWithin6_0_x(AuthenticationToken authenticationToken) {
        List<Integer> cryptoTokenIds = cryptoTokenSession.getCryptoTokenIds();
        for(int cryptoTokenId : cryptoTokenIds) {
            CryptoToken cryptoToken = cryptoTokenSession.getCryptoToken(cryptoTokenId);
            Properties oldProperties = cryptoToken.getProperties();
            if(!oldProperties.containsKey(PKCS11CryptoToken.SLOT_LABEL_VALUE)) {
                Properties newProperties = PKCS11CryptoToken.upgradePropertiesFileFrom5_0_x(oldProperties);
                cryptoToken.setProperties(newProperties);
                try {
                    cryptoTokenSession.mergeCryptoToken(cryptoToken);
                } catch (CryptoTokenNameInUseException e) {
                    //This should not be able to happen
                    throw new RuntimeException("Attempted to merge a crypto token with one ID to another with the same name.", e);
                }
            }
        }
    }
    
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    @Override
    public void activate(final AuthenticationToken authenticationToken, final int cryptoTokenId, final char[] authenticationCode) throws AuthorizationDeniedException, CryptoTokenOfflineException, CryptoTokenAuthenticationFailedException {
        assertAuthorization(authenticationToken, cryptoTokenId, CryptoTokenRules.ACTIVATE.resource()+"/"+cryptoTokenId);
        final CryptoToken cryptoToken = getCryptoTokenAndAssertExistence(cryptoTokenId);
        cryptoToken.activate(authenticationCode);
        securityEventsLoggerSession.log(EventTypes.CRYPTOTOKEN_ACTIVATE, EventStatus.SUCCESS, ModuleTypes.CRYPTOTOKEN, ServiceTypes.CORE, authenticationToken.toString(), String.valueOf(cryptoTokenId), null, null,
                "Activated CryptoToken '" + cryptoToken.getTokenName() + "' with id " + cryptoTokenId);
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    @Override
    public void deactivate(final AuthenticationToken authenticationToken, final int cryptoTokenId) throws AuthorizationDeniedException {
        assertAuthorization(authenticationToken, cryptoTokenId, CryptoTokenRules.DEACTIVATE.resource()+"/"+cryptoTokenId);
        final CryptoToken cryptoToken = getCryptoTokenAndAssertExistence(cryptoTokenId);
        cryptoToken.deactivate();
        securityEventsLoggerSession.log(EventTypes.CRYPTOTOKEN_DEACTIVATE, EventStatus.SUCCESS, ModuleTypes.CRYPTOTOKEN, ServiceTypes.CORE, authenticationToken.toString(), String.valueOf(cryptoTokenId), null, null,
                "Deactivated CryptoToken '" + cryptoToken.getTokenName() + "' with id " + cryptoTokenId);
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    @Override
    public boolean updatePin(AuthenticationToken authenticationToken, Integer cryptoTokenId, char[] currentAuthenticationCode,
            char[] newAuthenticationCode, boolean updateOnly) throws AuthorizationDeniedException, CryptoTokenAuthenticationFailedException, CryptoTokenOfflineException {
        final String[] requiredAuthorization = new String[] {
                CryptoTokenRules.MODIFY_CRYPTOTOKEN.resource()+"/"+cryptoTokenId,
                CryptoTokenRules.ACTIVATE.resource()+"/"+cryptoTokenId,
                CryptoTokenRules.DEACTIVATE.resource()+"/"+cryptoTokenId
        };
        if (!accessControlSessionSession.isAuthorized(authenticationToken,requiredAuthorization)) {
            final String msg = INTRES.getLocalizedMessage("authorization.notuathorizedtoresource", Arrays.toString(requiredAuthorization), authenticationToken.toString());
            throw new AuthorizationDeniedException(msg);
        }
        CryptoToken cryptoToken = getCryptoToken(cryptoTokenId);
        final Properties cryptoTokenProperties = cryptoToken.getProperties();
        // Get current auto-activation pin (if any)
        final String oldAutoActivationPin = BaseCryptoToken.getAutoActivatePin(cryptoTokenProperties);
        if (oldAutoActivationPin==null && (updateOnly || newAuthenticationCode==null)) {
            // This is a NOOP call that will not lead to any change
            return false;
        }
        if (SoftCryptoToken.class.getName().equals(cryptoToken.getClass().getName())) {
            CryptoProviderTools.installBCProviderIfNotAvailable();
            final KeyStore keystore;
            try {
                keystore = KeyStore.getInstance("PKCS12", "BC");
                keystore.load(new ByteArrayInputStream(cryptoToken.getTokenData()), currentAuthenticationCode);
            } catch (Exception e) {
                final String msg = "Failed to use supplied current PIN." + " " + e;
                log.info(msg);
                throw new CryptoTokenAuthenticationFailedException(msg);
            }
            if (newAuthenticationCode == null) {
                // When no new pin is supplied, we will not modify the key-store and just remove the current auto-activation pin
                cryptoTokenProperties.remove(CryptoToken.AUTOACTIVATE_PIN_PROPERTY);
                cryptoToken.setProperties(cryptoTokenProperties);
            } else {
                try {
                    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    keystore.store(baos, newAuthenticationCode);
                    baos.close();
                    if (oldAutoActivationPin!=null || !updateOnly) {
                        BaseCryptoToken.setAutoActivatePin(cryptoTokenProperties, new String(newAuthenticationCode), true);
                    } else {
                        log.debug("Auto-activation will not be used. Only changing pin for soft CryptoToken keystore.");
                    }
                    cryptoToken = CryptoTokenFactory.createCryptoToken(SoftCryptoToken.class.getName(), cryptoTokenProperties, baos.toByteArray(), cryptoTokenId, cryptoToken.getTokenName());
                } catch (Exception e) {
                    log.info("Unable to store soft keystore with new PIN: " + e);
                    throw new CryptoTokenAuthenticationFailedException("Unable to store soft keystore with new PIN");
                }
            }
        } else {
                if (oldAutoActivationPin!=null) {
                    // If we have an old auto-activation pin we will compare the "current" with this value to avoid deactivating the token
                    if (!oldAutoActivationPin.equals(new String(currentAuthenticationCode))) {
                        final String msg = "Supplied PIN did not match auto-activation PIN.";
                        log.info(msg);
                        throw new CryptoTokenAuthenticationFailedException(msg);
                    } else {
                        log.debug("Successfully verified the PIN for non-soft CryptoToken by comparing supplied PIN to auto-activation PIN.");
                    }
                } else {
                    // If we don't have an auto-activation pin to compare the supplied PIN to, we need to verify the supplied
                    // PIN can be used in a de-activation/activation cycle.
                    final boolean wasInactive = !isCryptoTokenStatusActive(authenticationToken, cryptoTokenId);
                    cryptoToken.deactivate();
                    cryptoToken.activate(currentAuthenticationCode);
                    if (wasInactive) {
                        // Note that there is a small glitch here where the token was active, but we have no other options to verify the pin
                        cryptoToken.deactivate();
                    }
                }
                if (newAuthenticationCode == null) {
                    cryptoTokenProperties.remove(CryptoToken.AUTOACTIVATE_PIN_PROPERTY);
                } else {
                    BaseCryptoToken.setAutoActivatePin(cryptoTokenProperties, new String(newAuthenticationCode), true);
                }
                cryptoToken.setProperties(cryptoTokenProperties);
        }
        // Save the modified CryptoToken
        try {
            cryptoTokenSession.mergeCryptoToken(cryptoToken);
        } catch (CryptoTokenNameInUseException e) {
            // This should not happen here since we use the same name and id
            throw new RuntimeException(e);
        }
        securityEventsLoggerSession.log(EventTypes.CRYPTOTOKEN_UPDATEPIN, EventStatus.SUCCESS, ModuleTypes.CRYPTOTOKEN, ServiceTypes.CORE, authenticationToken.toString(), String.valueOf(cryptoTokenId), null, null,
                "Updated PIN of CryptoToken '" + cryptoToken.getTokenName() + "' with id " + cryptoTokenId);
        // Return the current auto-activation state
        return BaseCryptoToken.getAutoActivatePin(cryptoTokenProperties) != null;
    }

    @Override
    public List<KeyPairInfo> getKeyPairInfos(final AuthenticationToken authenticationToken, final int cryptoTokenId) throws CryptoTokenOfflineException, AuthorizationDeniedException {
        assertAuthorization(authenticationToken, cryptoTokenId, CryptoTokenRules.VIEW.resource()+"/"+cryptoTokenId);
        final CryptoToken cryptoToken = getCryptoTokenAndAssertExistence(cryptoTokenId);
        final List<KeyPairInfo> ret = new ArrayList<KeyPairInfo>();
        for (final String alias : getKeyPairAliasesInternal(cryptoToken)) {
            final PublicKey publicKey = cryptoToken.getPublicKey(alias);
            final String keyAlgorithm = AlgorithmTools.getKeyAlgorithm(publicKey);
            final String keySpecification = AlgorithmTools.getKeySpecification(publicKey);
            final String subjectKeyId = new String(Hex.encode(KeyTools.createSubjectKeyId(publicKey).getKeyIdentifier()));
            ret.add(new KeyPairInfo(alias, keyAlgorithm, keySpecification, subjectKeyId));
        }
        return ret;
    }

    @Override
    public KeyPairInfo getKeyPairInfo(AuthenticationToken authenticationToken, int cryptoTokenId, String alias) throws CryptoTokenOfflineException, AuthorizationDeniedException {
        assertAuthorization(authenticationToken, cryptoTokenId, CryptoTokenRules.VIEW.resource()+"/"+cryptoTokenId);
        final CryptoToken cryptoToken = getCryptoTokenAndAssertExistence(cryptoTokenId);
        if (!getKeyPairAliasesInternal(cryptoToken).contains(alias)) {
            return null;
        }
        final PublicKey publicKey = cryptoToken.getPublicKey(alias);
        final String keyAlgorithm = AlgorithmTools.getKeyAlgorithm(publicKey);
        final String keySpecification = AlgorithmTools.getKeySpecification(publicKey);
        final String subjectKeyId = new String(Hex.encode(KeyTools.createSubjectKeyId(publicKey).getKeyIdentifier()));
        return new KeyPairInfo(alias, keyAlgorithm, keySpecification, subjectKeyId);
    }

    @Override
    public PublicKey getPublicKey(AuthenticationToken authenticationToken, int cryptoTokenId, String alias) throws AuthorizationDeniedException, CryptoTokenOfflineException {
        assertAuthorization(authenticationToken, cryptoTokenId, CryptoTokenRules.VIEW.resource()+"/"+cryptoTokenId);
        return  getCryptoTokenAndAssertExistence(cryptoTokenId).getPublicKey(alias);
    }

    @Override
    public Integer getIdFromName(final String cryptoTokenName) {
        if (cryptoTokenName==null) {
            return null;
        }
        final Map<String, Integer> cachedNameToIdMap = cryptoTokenSession.getCachedNameToIdMap();
        Integer cryptoTokenId = cachedNameToIdMap.get(cryptoTokenName);
        if (cryptoTokenId == null) {
            // Ok.. so it's not in the cache.. look for it the hard way..
            for (final Integer currentCryptoTokenId : cryptoTokenSession.getCryptoTokenIds()) {
                // Don't lookup CryptoTokens we already have in the id to name cache
                if (!cachedNameToIdMap.keySet().contains(currentCryptoTokenId)) {
                    final CryptoToken currentCryptoToken = cryptoTokenSession.getCryptoToken(currentCryptoTokenId.intValue());
                    final String currentCryptoTokenName = currentCryptoToken == null ? null : currentCryptoToken.getTokenName();
                    if (cryptoTokenName.equals(currentCryptoTokenName)) {
                        cryptoTokenId = currentCryptoTokenId;
                        break;
                    }
                }
            }
        }
        return cryptoTokenId;
    }

    @Override
    public List<String> getKeyPairAliases(final AuthenticationToken authenticationToken, final int cryptoTokenId) throws AuthorizationDeniedException, CryptoTokenOfflineException {
        assertAuthorization(authenticationToken, cryptoTokenId, CryptoTokenRules.VIEW.resource()+"/"+cryptoTokenId);
        final CryptoToken cryptoToken = getCryptoTokenAndAssertExistence(cryptoTokenId);
        return getKeyPairAliasesInternal(cryptoToken);
    }

    private List<String> getKeyPairAliasesInternal(final CryptoToken cryptoToken) throws CryptoTokenOfflineException {
        try {
            final Enumeration<String> aliasEnumeration = cryptoToken.getAliases();
            final List<String> keyPairAliases = new ArrayList<String>();
            while (aliasEnumeration.hasMoreElements()) {
                final String currentAlias = aliasEnumeration.nextElement();
                if (cryptoToken.getPublicKey(currentAlias) != null && cryptoToken.getPrivateKey(currentAlias) != null) {
                    // A key pair exists for this alias, so add it
                    keyPairAliases.add(currentAlias);
                }
            }
            return keyPairAliases;
        } catch (KeyStoreException e) {
            throw new CryptoTokenOfflineException(e);
        }
    }

    @Override
    public void createKeyPair(final AuthenticationToken authenticationToken, final int cryptoTokenId, final String alias, final String keySpecificationParam)
            throws AuthorizationDeniedException, CryptoTokenOfflineException, InvalidKeyException, InvalidAlgorithmParameterException {
        assertAuthorization(authenticationToken, cryptoTokenId, CryptoTokenRules.GENERATE_KEYS.resource()+"/"+cryptoTokenId);
        final CryptoToken cryptoToken = getCryptoTokenAndAssertExistence(cryptoTokenId);
        // Check if alias is already in use
        assertAliasNotInUse(cryptoToken, alias);

        // Support "RSAnnnn" and convert it to the legacy format "nnnn"
        final String keySpecification;
        if (keySpecificationParam.startsWith(AlgorithmConstants.KEYALGORITHM_RSA)) {
            keySpecification = keySpecificationParam.substring(AlgorithmConstants.KEYALGORITHM_RSA.length());
        } else {
            keySpecification = keySpecificationParam;
        }
        // Check if keySpec is valid
        KeyTools.checkValidKeyLength(keySpecification);
        // Audit log before generation. If the token is an HSM the merge will not make a difference.
        final Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("msg", "Generated new keypair in CryptoToken " + cryptoTokenId);
        details.put("keyAlias", alias);
        details.put("keySpecification", keySpecification);
        cryptoToken.generateKeyPair(keySpecification, alias);
        cryptoToken.testKeyPair(alias);
        // Merge is important for soft tokens where the data is persisted in the database, but will also update lastUpdate
        try {
            cryptoTokenSession.mergeCryptoToken(cryptoToken);
        } catch (CryptoTokenNameInUseException e) {
            throw new RuntimeException(e);  // We have not changed the name of the CrytpoToken here, so this should never happen
        }
        securityEventsLoggerSession.log(EventTypes.CRYPTOTOKEN_GEN_KEYPAIR, EventStatus.SUCCESS, ModuleTypes.CRYPTOTOKEN, ServiceTypes.CORE,
                authenticationToken.toString(), String.valueOf(cryptoTokenId), null, null, details);
    }

    @Override
    public void createKeyPairWithSameKeySpec(final AuthenticationToken authenticationToken, final int cryptoTokenId, final String currentAlias, final String newAlias)
            throws AuthorizationDeniedException, CryptoTokenOfflineException, InvalidKeyException, InvalidAlgorithmParameterException {
        assertAuthorization(authenticationToken, cryptoTokenId,
                CryptoTokenRules.GENERATE_KEYS.resource() + "/" + cryptoTokenId);
        final CryptoToken cryptoToken = getCryptoTokenAndAssertExistence(cryptoTokenId);
        assertAliasNotInUse(cryptoToken, newAlias);
        final PublicKey publicKey = cryptoToken.getPublicKey(currentAlias);
        final String keyAlgorithm = AlgorithmTools.getKeyAlgorithm(publicKey);
        final String keySpecification;
        if (AlgorithmConstants.KEYALGORITHM_DSA.equals(keyAlgorithm)) {
            keySpecification = AlgorithmConstants.KEYALGORITHM_DSA + AlgorithmTools.getKeySpecification(publicKey);
        } else {
            keySpecification = AlgorithmTools.getKeySpecification(publicKey);
        }
        KeyTools.checkValidKeyLength(keySpecification);
        final Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("msg", "Generated new keypair in CryptoToken " + cryptoTokenId);
        details.put("keyAlias", newAlias);
        details.put("keySpecification", keySpecification);
        cryptoToken.generateKeyPair(keySpecification, newAlias);
        cryptoToken.testKeyPair(newAlias);
        try {
            cryptoTokenSession.mergeCryptoToken(cryptoToken);
        } catch (CryptoTokenNameInUseException e) {
            throw new RuntimeException(e);  // We have not changed the name of the CrytpoToken here, so this should never happen
        }
        securityEventsLoggerSession.log(EventTypes.CRYPTOTOKEN_GEN_KEYPAIR, EventStatus.SUCCESS, ModuleTypes.CRYPTOTOKEN, ServiceTypes.CORE,
                authenticationToken.toString(), String.valueOf(cryptoTokenId), null, null, details);
    }

    @Override
    public boolean isAliasUsedInCryptoToken(final int cryptoTokenId, final String alias) {
        return isAliasUsed(getCryptoToken(cryptoTokenId), alias);
    }
    
    /** @return true if there is a private, public or symmetric entry with this alias in the CryptoToken */
    private boolean isAliasUsed(final CryptoToken cryptoToken, final String alias) {
        boolean aliasInUse = false;
        try {
            cryptoToken.getPublicKey(alias);
            aliasInUse = true;
        } catch (CryptoTokenOfflineException e) {
            try {
                cryptoToken.getPrivateKey(alias);
                aliasInUse = true;
            } catch (CryptoTokenOfflineException e2) {
                try {
                    cryptoToken.getKey(alias);
                    aliasInUse = true;
                } catch (CryptoTokenOfflineException e3) {
                }
            }
        }
        return aliasInUse;
    }
    
    /** @throws InvalidKeyException if the alias is in use by a private, public or symmetric key */
    private void assertAliasNotInUse(final CryptoToken cryptoToken, final String alias) throws InvalidKeyException {
        if (isAliasUsed(cryptoToken, alias)) {
            throw new InvalidKeyException("alias " + alias + " is in use");
        }
    }

    @Override
    public void removeKeyPair(final AuthenticationToken authenticationToken, final int cryptoTokenId, final String alias)
            throws AuthorizationDeniedException, CryptoTokenOfflineException, InvalidKeyException {
        assertAuthorization(authenticationToken, cryptoTokenId, CryptoTokenRules.REMOVE_KEYS.resource()+"/"+cryptoTokenId);
        final CryptoToken cryptoToken = getCryptoTokenAndAssertExistence(cryptoTokenId);
        // Check if alias is in use
        if (!isAliasUsed(cryptoToken, alias)) {
            throw new InvalidKeyException("Alias " + alias + " is not in use");
        }
        final Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("msg", "Deleted key pair from CryptoToken " + cryptoTokenId);
        details.put("keyAlias", alias);
        try {
            cryptoToken.deleteEntry(alias);
        } catch (KeyStoreException e) {
            throw new InvalidKeyException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new InvalidKeyException(e);
        } catch (CertificateException e) {
            throw new InvalidKeyException(e);
        } catch (IOException e) {
            throw new InvalidKeyException(e);
        }
        assertAliasNotInUse(cryptoToken, alias);
        log.debug("cryptoTokenSession.mergeCryptoToken");
        // Merge is important for soft tokens where the data is persisted in the database, but will also update lastUpdate
        try {
            cryptoTokenSession.mergeCryptoToken(cryptoToken);
        } catch (CryptoTokenNameInUseException e) {
            throw new RuntimeException(e);  // We have not changed the name of the CrytpoToken here, so this should never happen
        }
        securityEventsLoggerSession.log(EventTypes.CRYPTOTOKEN_DELETE_ENTRY, EventStatus.SUCCESS, ModuleTypes.CRYPTOTOKEN, ServiceTypes.CORE, authenticationToken.toString(), String.valueOf(cryptoTokenId), null, null, details);
    }

    @Override
    public void testKeyPair(final AuthenticationToken authenticationToken, final int cryptoTokenId, final String alias)
            throws AuthorizationDeniedException, CryptoTokenOfflineException, InvalidKeyException {
         assertAuthorization(authenticationToken, cryptoTokenId,
                CryptoTokenRules.TEST_KEYS.resource() + "/" + cryptoTokenId);
         final CryptoToken cryptoToken = getCryptoTokenAndAssertExistence(cryptoTokenId);
        cryptoToken.testKeyPair(alias);
    }
    
    /** @return a CryptoToken for the requested Id of authorized and it exists. Never returns null. */
    private void assertAuthorization(final AuthenticationToken authenticationToken, final int cryptoTokenId, final String resource) throws AuthorizationDeniedException {
        if (!accessControlSessionSession.isAuthorized(authenticationToken, resource)) {
            final String msg = INTRES.getLocalizedMessage("authorization.notuathorizedtoresource", resource, authenticationToken.toString());
            throw new AuthorizationDeniedException(msg);
        }

    }
    
    private CryptoToken getCryptoTokenAndAssertExistence(int cryptoTokenId) {
        final CryptoToken cryptoToken = cryptoTokenSession.getCryptoToken(cryptoTokenId);
        if (cryptoToken == null) {
            throw new RuntimeException("No such CryptoToken for id " + cryptoTokenId);
        }
        return cryptoToken;
    }

    /** Helper method for audit logging changes */
    private void putDelta(Properties oldProperties, Properties newProperties, Map<String, Object> details) {
        // Find out what has happended to all the old properties
        for (final Object key : oldProperties.keySet()) {
            final String oldValue = oldProperties.getProperty(String.valueOf(key));
            final String newValue = newProperties.getProperty(String.valueOf(key));
            putDelta(String.valueOf(key), oldValue, newValue, details);
        }
        // Find out which new properties that did not exist in the old
        for (final Object key : newProperties.keySet()) {
            final String oldValue = oldProperties.getProperty(String.valueOf(key));
            if (oldValue==null) {
                final String newValue = newProperties.getProperty(String.valueOf(key));
                putDelta(String.valueOf(key), oldValue, newValue, details);
            }
        }
    }

    /** Helper method for audit logging changes */
    private void putDelta(String key, String oldValue, String newValue, Map<String, Object> details) {
        // Treat the auto-activation pin with care
        if (BaseCryptoToken.AUTOACTIVATE_PIN_PROPERTY.equals(key)) {
            if (oldValue == null && newValue == null) {
                // NOP
            } else if (oldValue == null && newValue != null) {
                details.put("autoActivation", "added");
            } else if (oldValue != null && newValue == null) {
                details.put("autoActivation", "removed");
            } else if (!oldValue.equals(newValue)) {
                details.put("autoActivation", "pin changed");
            }
        } else {
            if (oldValue == null && newValue == null) {
                // NOP
            } else if (oldValue == null && newValue != null) {
                details.put("added:"+key, newValue);
            } else if (oldValue != null && newValue == null) {
                details.put("removed:"+key, oldValue);
            } else if (!oldValue.equals(newValue)) {
                details.put("changed:"+key, newValue);
            } else {
                details.put(key, newValue);
            }
        }
    }
}