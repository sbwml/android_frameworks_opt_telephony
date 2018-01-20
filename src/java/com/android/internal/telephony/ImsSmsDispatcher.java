/*
 * Copyright (C) 2018 The Android Open Source Project
 *
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
 * limitations under the License
 */

package com.android.internal.telephony;

import android.app.Activity;
import android.os.RemoteException;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.provider.Telephony.Sms;
import android.content.Intent;
import android.telephony.Rlog;

import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.ims.internal.IImsSmsListener;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.GsmAlphabet.TextEncodingDetails;
import com.android.internal.telephony.util.SMSDispatcherUtil;
import com.android.internal.telephony.gsm.SmsMessage;
import android.telephony.ims.internal.SmsImplBase;
import android.telephony.ims.internal.SmsImplBase.SendStatusResult;
import android.telephony.ims.internal.SmsImplBase.StatusReportResult;
import android.provider.Telephony.Sms.Intents;
import android.util.Pair;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import libcore.util.Nullable;

/**
 * Responsible for communications with {@link com.android.ims.ImsManager} to send/receive messages
 * over IMS.
 */
public class ImsSmsDispatcher extends SMSDispatcher {
    @VisibleForTesting
    public Map<Integer, SmsTracker> mTrackers = new ConcurrentHashMap<>();
    @VisibleForTesting
    public AtomicInteger mNextToken = new AtomicInteger();

    private final IImsSmsListener mImsSmsListener = new IImsSmsListener.Stub() {
        @Override
        public void onSendSmsResult(int token, int messageRef, @SendStatusResult int status,
                int reason) throws RemoteException {
            SmsTracker tracker = mTrackers.get(token);
            if (tracker == null) {
                throw new IllegalArgumentException("Invalid token.");
            }
            switch(reason) {
                case SmsImplBase.SEND_STATUS_OK:
                    tracker.onSent(mContext);
                    break;
                case SmsImplBase.SEND_STATUS_ERROR:
                    tracker.onFailed(mContext, reason, 0 /* errorCode */);
                    mTrackers.remove(token);
                    break;
                case SmsImplBase.SEND_STATUS_ERROR_RETRY:
                    tracker.mRetryCount += 1;
                    sendSms(tracker);
                    break;
                case SmsImplBase.SEND_STATUS_ERROR_FALLBACK:
                    fallbackToPstn(token, tracker);
                    break;
                default:
            }
        }

        @Override
        public void onSmsStatusReportReceived(int token, int messageRef, String format, byte[] pdu)
                throws RemoteException {
            Rlog.d(TAG, "Status report received.");
            SmsTracker tracker = mTrackers.get(token);
            if (tracker == null) {
                throw new RemoteException("Invalid token.");
            }
            Pair<Boolean, Boolean> result = mSmsDispatchersController.handleSmsStatusReport(
                    tracker, format, pdu);
            Rlog.d(TAG, "Status report handle result, success: " + result.first +
                    "complete: " + result.second);
            try {
                getImsManager().acknowledgeSmsReport(
                        token,
                        messageRef,
                        result.first ? SmsImplBase.STATUS_REPORT_STATUS_OK
                                : SmsImplBase.STATUS_REPORT_STATUS_ERROR);
            } catch (ImsException e) {
                Rlog.e(TAG, "Failed to acknowledgeSmsReport(). Error: "
                        + e.getMessage());
            }
            if (result.second) {
                mTrackers.remove(token);
            }
        }

        @Override
        public void onSmsReceived(int token, String format, byte[] pdu)
                throws RemoteException {
            Rlog.d(TAG, "SMS received.");
            mSmsDispatchersController.injectSmsPdu(pdu, format, result -> {
                Rlog.d(TAG, "SMS handled result: " + result);
                try {
                    getImsManager().acknowledgeSms(token,
                            0,
                            result == Intents.RESULT_SMS_HANDLED
                                    ? SmsImplBase.STATUS_REPORT_STATUS_OK
                                    : SmsImplBase.DELIVER_STATUS_ERROR);
                } catch (ImsException e) {
                    Rlog.e(TAG, "Failed to acknowledgeSms(). Error: " + e.getMessage());
                }
            });
        }
    };

    public ImsSmsDispatcher(Phone phone, SmsDispatchersController smsDispatchersController) {
        super(phone, smsDispatchersController);
    }

    public boolean isAvailable() {
        // TODO: implement
        return false;
    }

    @Override
    protected String getFormat() {
        try {
            return getImsManager().getSmsFormat();
        } catch (ImsException e) {
            Rlog.e(TAG, "Failed to get sms format. Error: " + e.getMessage());
            return SmsConstants.FORMAT_UNKNOWN;
        }
    }

    @Override
    protected boolean shouldBlockSms() {
        return SMSDispatcherUtil.shouldBlockSms(isCdmaMo(), mPhone);
    }

    @Override
    protected SmsMessageBase.SubmitPduBase getSubmitPdu(String scAddr, String destAddr,
            String message, boolean statusReportRequested, SmsHeader smsHeader) {
        return SMSDispatcherUtil.getSubmitPdu(isCdmaMo(), scAddr, destAddr, message,
                statusReportRequested, smsHeader);
    }

    @Override
    protected SmsMessageBase.SubmitPduBase getSubmitPdu(String scAddr, String destAddr,
            int destPort, byte[] message, boolean statusReportRequested) {
        return SMSDispatcherUtil.getSubmitPdu(isCdmaMo(), scAddr, destAddr, destPort, message,
                statusReportRequested);
    }

    @Override
    protected TextEncodingDetails calculateLength(CharSequence messageBody, boolean use7bitOnly) {
        return SMSDispatcherUtil.calculateLength(isCdmaMo(), messageBody, use7bitOnly);
    }

    @Override
    public void sendSms(SmsTracker tracker) {
        Rlog.d(TAG, "sendSms: "
                + " mRetryCount=" + tracker.mRetryCount
                + " mMessageRef=" + tracker.mMessageRef
                + " SS=" + mPhone.getServiceState().getState());

        HashMap<String, Object> map = tracker.getData();

        byte[] pdu = (byte[]) map.get(MAP_KEY_PDU);
        byte smsc[] = (byte[]) map.get(MAP_KEY_SMSC);
        boolean isRetry = tracker.mRetryCount > 0;

        if (SmsConstants.FORMAT_3GPP.equals(getFormat()) && tracker.mRetryCount > 0) {
            // per TS 23.040 Section 9.2.3.6:  If TP-MTI SMS-SUBMIT (0x01) type
            //   TP-RD (bit 2) is 1 for retry
            //   and TP-MR is set to previously failed sms TP-MR
            if (((0x01 & pdu[0]) == 0x01)) {
                pdu[0] |= 0x04; // TP-RD
                pdu[1] = (byte) tracker.mMessageRef; // TP-MR
            }
        }

        int token = mNextToken.incrementAndGet();
        mTrackers.put(token, tracker);
        try {
            getImsManager().sendSms(
                    token,
                    tracker.mMessageRef,
                    getFormat(),
                    smsc != null ? new String(smsc) : null,
                    isRetry,
                    pdu);
        } catch (ImsException e) {
            Rlog.e(TAG, "sendSms failed. Falling back to PSTN. Error: " + e.getMessage());
            fallbackToPstn(token, tracker);
        }
    }

    private ImsManager getImsManager() {
        return ImsManager.getInstance(mContext, mPhone.getPhoneId());
    }

    @VisibleForTesting
    public void fallbackToPstn(int token, SmsTracker tracker) {
        mSmsDispatchersController.sendRetrySms(tracker);
        mTrackers.remove(token);
    }

    @Override
    protected boolean isCdmaMo() {
        return mSmsDispatchersController.isCdmaFormat(getFormat());
    }
}