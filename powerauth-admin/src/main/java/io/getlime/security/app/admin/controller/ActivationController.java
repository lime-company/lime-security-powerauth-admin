/*
 * Copyright 2017 Wultra s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.getlime.security.app.admin.controller;

import io.getlime.security.app.admin.converter.SignatureAuditItemConverter;
import io.getlime.security.app.admin.model.SignatureAuditItem;
import io.getlime.security.app.admin.util.QRUtil;
import io.getlime.powerauth.soap.v3.*;
import io.getlime.security.powerauth.soap.spring.client.PowerAuthServiceClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.ws.soap.client.SoapFaultClientException;

import java.security.Principal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Controller class related to PowerAuth activation management.
 *
 * @author Petr Dvorak
 */
@Controller
public class ActivationController {

    private final PowerAuthServiceClient client;

    @Autowired
    public ActivationController(PowerAuthServiceClient client) {
        this.client = client;
    }

    private final SignatureAuditItemConverter signatureAuditItemConverter = new SignatureAuditItemConverter();

    /**
     * Return the list of activations for given users.
     *
     * @param userId User ID to lookup the activations for.
     * @param showAllActivations Indicates if activations in REMOVED state should be returned.
     * @param showAllRecoveryCodes Indicates if recovery codes in REVOKED state should be returned.
     * @param model Model with passed parameters.
     * @return "activations" view.
     */
    @RequestMapping(value = "/activation/list")
    public String activationList(@RequestParam(value = "userId", required = false) String userId, @RequestParam(value = "showAllActivations", required = false) Boolean showAllActivations,
                                 @RequestParam(value = "showAllRecoveryCodes", required = false) Boolean showAllRecoveryCodes, Map<String, Object> model) {
        if (userId != null) {
            List<GetActivationListForUserResponse.Activations> activationList = client.getActivationListForUser(userId);
            Collections.sort(activationList, new Comparator<GetActivationListForUserResponse.Activations>() {

                @Override
                public int compare(GetActivationListForUserResponse.Activations o1, GetActivationListForUserResponse.Activations o2) {
                    return o2.getTimestampLastUsed().compare(o1.getTimestampLastUsed());
                }

            });

            model.put("activations", activationList);
            model.put("userId", userId);
            model.put("showAllActivations", showAllActivations);
            model.put("showAllRecoveryCodes", showAllRecoveryCodes);

            List<GetApplicationListResponse.Applications> applications = client.getApplicationList();
            model.put("applications", applications);

            LookupRecoveryCodesResponse response = client.lookupRecoveryCodes(userId, null, null, null, null);
            model.put("recoveryCodes", response.getRecoveryCodes());
        }
        return "activations";
    }

    /**
     * Get detail of a given activation.
     *
     * @param id    Activation ID.
     * @param fromDate Optional filter for date from.
     * @param toDate Optional filter for date to.
     * @param model Model with passed parameters.
     * @return "activationDetail" view.
     */
    @RequestMapping(value = "/activation/detail/{id}")
    public String activationDetail(
            @PathVariable(value = "id") String id,
            @RequestParam(value = "fromDate", required = false) String fromDate,
            @RequestParam(value = "toDate", required = false) String toDate,
            Map<String, Object> model) {

        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date startingDate;
        Date endingDate;
        Date currentTimePlusOneSecond;
        Calendar cal = Calendar.getInstance();
        // Add one second to avoid filtering out the most recent signatures and activation changes.
        cal.add(Calendar.SECOND, 1);
        currentTimePlusOneSecond = cal.getTime();
        try {
            if (toDate != null) {
                endingDate = dateFormat.parse(toDate);
            } else {
                endingDate = currentTimePlusOneSecond;
                toDate = dateFormat.format(endingDate);
            }
            model.put("toDate", toDate);
            if (fromDate != null) {
                startingDate = dateFormat.parse(fromDate);
            } else {
                startingDate = new Date(endingDate.getTime() - (30L * 24L * 60L * 60L * 1000L));
                fromDate = dateFormat.format(startingDate);
            }
            model.put("fromDate", fromDate);
        } catch (ParseException e) {
            // Date parsing didn't work, OK - clear the values...
            endingDate = currentTimePlusOneSecond;
            startingDate = new Date(endingDate.getTime() - (30L * 24L * 60L * 60L * 1000L));
            fromDate = dateFormat.format(startingDate);
            toDate = dateFormat.format(endingDate);
            model.put("fromDate", fromDate);
            model.put("toDate", toDate);
        }

        GetActivationStatusResponse activation = client.getActivationStatus(id);
        model.put("activationId", activation.getActivationId());
        model.put("activationName", activation.getActivationName());
        model.put("status", activation.getActivationStatus());
        model.put("blockedReason", activation.getBlockedReason());
        model.put("timestampCreated", activation.getTimestampCreated());
        model.put("timestampLastUsed", activation.getTimestampLastUsed());
        model.put("activationFingerprint", activation.getDevicePublicKeyFingerprint());
        model.put("userId", activation.getUserId());
        model.put("version", activation.getVersion());
        model.put("platform", activation.getPlatform());
        model.put("deviceInfo", activation.getDeviceInfo());
        if (activation.getActivationStatus() == ActivationStatus.PENDING_COMMIT && activation.getActivationOtpValidation() == ActivationOtpValidation.ON_COMMIT) {
            model.put("showOtpInput", true);
        } else {
            model.put("showOtpInput", false);
        }

        GetApplicationDetailResponse application = client.getApplicationDetail(activation.getApplicationId());
        model.put("applicationId", application.getApplicationId());
        model.put("applicationName", application.getApplicationName());

        LookupRecoveryCodesResponse response = client.lookupRecoveryCodes(activation.getUserId(), activation.getActivationId(), activation.getApplicationId(), null, null);
        model.put("recoveryCodes", response.getRecoveryCodes());

        List<SignatureAuditResponse.Items> auditItems = client.getSignatureAuditLog(activation.getUserId(), application.getApplicationId(), startingDate, endingDate);
        List<SignatureAuditItem> auditItemsFixed = new ArrayList<>();
        for (SignatureAuditResponse.Items item : auditItems) {
            if (item.getActivationId().equals(activation.getActivationId())) {
                auditItemsFixed.add(signatureAuditItemConverter.fromSignatureAuditResponseItem(item));
            }
        }
        if (auditItemsFixed.size() > 100) {
            auditItemsFixed = auditItemsFixed.subList(0, 100);
        }
        model.put("signatures", auditItemsFixed);

        List<ActivationHistoryResponse.Items> activationHistoryItems = client.getActivationHistory(activation.getActivationId(), startingDate, endingDate);
        List<ActivationHistoryResponse.Items> trimmedActivationHistoryItems = new ArrayList<>();
        if (activationHistoryItems.size() > 100) {
            trimmedActivationHistoryItems = activationHistoryItems.subList(0, 100);
        } else {
            trimmedActivationHistoryItems = activationHistoryItems;
        }
        model.put("history", trimmedActivationHistoryItems);

        if (activation.getActivationStatus().equals(ActivationStatus.CREATED)) {
            String activationSignature = activation.getActivationSignature();
            model.put("activationCode", activation.getActivationCode());
            model.put("activationSignature", activationSignature);
            model.put("activationQR", QRUtil.encode(activation.getActivationCode() + "#" + activationSignature, 400));
        }

        return "activationDetail";
    }

    /**
     * Create a new activation.
     *
     * @param applicationId           Application ID of an associated application.
     * @param userId                  User ID.
     * @param activationOtpValidation Activation OTP validation mode.
     * @param activationOtp           Activation OTP code.
     * @param model                   Model with passed parameters.
     * @param redirectAttributes      Redirect attributes.
     * @return Redirect the user to activation detail.
     */
    @RequestMapping(value = "/activation/create")
    public String activationCreate(@RequestParam(value = "applicationId") Long applicationId, @RequestParam(value = "userId") String userId,
                                   @RequestParam(value = "activationOtpValidation") String activationOtpValidation,
                                   @RequestParam(value = "activationOtp") String activationOtp,
                                   Map<String, Object> model, RedirectAttributes redirectAttributes) {
        InitActivationResponse response;

        if (!"NONE".equals(activationOtpValidation) && (activationOtp == null || activationOtp.isEmpty())) {
            redirectAttributes.addFlashAttribute("error", "Please specify the OTP validation code.");
            return "redirect:/activation/list?userId=" + userId;
        }
        switch (activationOtpValidation) {
            case "NONE":
                response = client.initActivation(userId, applicationId);
                break;

            case "ON_KEY_EXCHANGE":
                response = client.initActivation(userId, applicationId, ActivationOtpValidation.ON_KEY_EXCHANGE, activationOtp);
                break;

            case "ON_COMMIT":
                response = client.initActivation(userId, applicationId, ActivationOtpValidation.ON_COMMIT, activationOtp);
                break;

            default:
                redirectAttributes.addFlashAttribute("error", "Invalid OTP validation mode.");
                return "redirect:/activation/list?userId=" + userId;
        }


        model.put("activationCode", response.getActivationCode());
        model.put("activationId", response.getActivationId());
        model.put("activationSignature", response.getActivationSignature());

        return "redirect:/activation/detail/" + response.getActivationId();
    }

    /**
     * Commit activation.
     *
     * @param activationId Activation ID.
     * @param model        Model with passed parameters.
     * @param principal    Principal entity.
     * @return Redirect the user to activation detail.
     */
    @RequestMapping(value = "/activation/create/do.submit", method = RequestMethod.POST)
    public String activationCreateCommitAction(@RequestParam(value = "activationId") String activationId, Map<String, Object> model, Principal principal) {
        String username = extractUsername(principal);
        CommitActivationResponse commitActivation = client.commitActivation(activationId, username);
        return "redirect:/activation/detail/" + commitActivation.getActivationId();
    }

    /**
     * Block activation.
     *
     * @param activationId Activation ID
     * @param userId       User ID identifying user for redirect to the list of activations
     * @param model        Model with passed parameters.
     * @param principal    Principal entity.
     * @return Redirect user to given URL or to activation detail, in case 'redirect' is null or empty.
     */
    @RequestMapping(value = "/activation/block/do.submit", method = RequestMethod.POST)
    public String blockActivation(@RequestParam(value = "activationId") String activationId, @RequestParam(value = "redirectUserId") String userId, Map<String, Object> model, Principal principal) {
        String username = extractUsername(principal);
        BlockActivationResponse blockActivation = client.blockActivation(activationId, null, username);
        if (userId != null && !userId.trim().isEmpty()) {
            return "redirect:/activation/list?userId=" + userId;
        }
        return "redirect:/activation/detail/" + blockActivation.getActivationId();
    }

    /**
     * Unblock activation.
     *
     * @param activationId Activation ID
     * @param userId       User ID identifying user for redirect to the list of activations
     * @param model        Model with passed parameters.
     * @param principal    Principal entity.
     * @return Redirect user to given URL or to activation detail, in case 'redirect' is null or empty.
     */
    @RequestMapping(value = "/activation/unblock/do.submit", method = RequestMethod.POST)
    public String unblockActivation(@RequestParam(value = "activationId") String activationId, @RequestParam(value = "redirectUserId") String userId, Map<String, Object> model, Principal principal) {
        String username = extractUsername(principal);
        UnblockActivationResponse unblockActivation = client.unblockActivation(activationId, username);
        if (userId != null && !userId.trim().isEmpty()) {
            return "redirect:/activation/list?userId=" + userId;
        }
        return "redirect:/activation/detail/" + unblockActivation.getActivationId();
    }

    /**
     * Commit activation.
     *
     * @param activationId       Activation ID.
     * @param userId             User ID identifying user for redirect to the list of activations.
     * @param activationOtp      Activation OTP code.
     * @param model              Model with passed parameters.
     * @param principal          Principal entity.
     * @param redirectAttributes Redirect attributes.
     * @return Redirect user to given URL or to activation detail, in case 'redirect' is null or empty.
     */
    @RequestMapping(value = "/activation/commit/do.submit", method = RequestMethod.POST)
    public String commitActivation(@RequestParam(value = "activationId") String activationId,
                                   @RequestParam(value = "redirectUserId") String userId,
                                   @RequestParam(value = "activationOtp", required = false) String activationOtp,
                                   Map<String, Object> model, Principal principal,
                                   RedirectAttributes redirectAttributes) {
        String username = extractUsername(principal);
        CommitActivationRequest request = new CommitActivationRequest();
        request.setActivationId(activationId);
        request.setExternalUserId(username);
        if (activationOtp != null) {
            request.setActivationOtp(activationOtp);
        }
        try {
            CommitActivationResponse commitActivation = client.commitActivation(request);
            if (userId != null && !userId.trim().isEmpty()) {
                return "redirect:/activation/list?userId=" + userId;
            }
            return "redirect:/activation/detail/" + commitActivation.getActivationId();
        } catch (SoapFaultClientException ex) {
            redirectAttributes.addFlashAttribute("error", "Activation commit failed.");
            return "redirect:/activation/detail/" + activationId;
        }
    }

    /**
     * Remove activation.
     *
     * @param activationId Activation ID
     * @param userId       User ID identifying user for redirect to the list of activations
     * @param model        Model with passed parameters.
     * @param principal    Principal entity.
     * @return Redirect user to given URL or to activation detail, in case 'redirect' is null or empty.
     */
    @RequestMapping(value = "/activation/remove/do.submit", method = RequestMethod.POST)
    public String removeActivation(@RequestParam(value = "activationId") String activationId, @RequestParam(value = "redirectUserId") String userId, Map<String, Object> model, Principal principal) {
        String username = extractUsername(principal);
        RemoveActivationResponse removeActivation = client.removeActivation(activationId, username);
        if (userId != null && !userId.trim().isEmpty()) {
            return "redirect:/activation/list?userId=" + userId;
        }
        return "redirect:/activation/detail/" + removeActivation.getActivationId() + "#versions";
    }

    /**
     * Revoke recovery code.
     * @param recoveryCodeId Recovery code ID.
     * @param activationId Activation ID.
     * @param userId User ID.
     * @param model Request model.
     * @return Redirect user to given URL or to activation detail - recovery tab, in case 'redirect' is null or empty.
     */
    @RequestMapping(value = "/activation/recovery/revoke/do.submit", method = RequestMethod.POST)
    public String revokeRecoveryCode(@RequestParam(value = "recoveryCodeId") Long recoveryCodeId, @RequestParam(value = "activationId", required = false) String activationId,
                                     @RequestParam(value = "userId", required = false) String userId, Map<String, Object> model) {
        List<Long> recoveryCodeIds = new ArrayList<>();
        recoveryCodeIds.add(recoveryCodeId);
        client.revokeRecoveryCodes(recoveryCodeIds);
        if (activationId != null) {
            return "redirect:/activation/detail/" + activationId + "#recovery";
        } else {
            return "redirect:/activation/list?userId=" + userId;
        }
    }

    /**
     * Extract username from principal.
     * @param principal Principal entity.
     * @return Extracted username or null for principal without authentication.
     */
    private String extractUsername(Principal principal) {
        if (principal == null || "anonymous".equals(principal.getName())) {
            return null;
        }
        return principal.getName();
    }

}
