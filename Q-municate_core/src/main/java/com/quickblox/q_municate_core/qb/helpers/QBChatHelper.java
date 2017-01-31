package com.quickblox.q_municate_core.qb.helpers;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;

import com.quickblox.chat.JIDHelper;
import com.quickblox.chat.QBChatService;
import com.quickblox.chat.QBPrivateChat;
import com.quickblox.chat.QBRestChatService;
import com.quickblox.chat.QBSystemMessagesManager;
import com.quickblox.chat.exception.QBChatException;
import com.quickblox.chat.listeners.QBChatDialogMessageListener;
import com.quickblox.chat.listeners.QBChatDialogParticipantListener;
import com.quickblox.chat.listeners.QBChatDialogTypingListener;
import com.quickblox.chat.listeners.QBMessageStatusListener;
import com.quickblox.chat.listeners.QBPrivateChatManagerListener;
import com.quickblox.chat.listeners.QBSystemMessageListener;
import com.quickblox.chat.model.QBAttachment;
import com.quickblox.chat.model.QBChatMessage;
import com.quickblox.chat.model.QBChatDialog;
import com.quickblox.chat.model.QBDialogType;
import com.quickblox.chat.model.QBPresence;
import com.quickblox.chat.utils.DialogUtils;
import com.quickblox.content.QBContent;
import com.quickblox.content.model.QBFile;
import com.quickblox.core.exception.QBResponseException;
import com.quickblox.core.helper.StringifyArrayList;
import com.quickblox.core.request.QBRequestGetBuilder;
import com.quickblox.core.request.QBRequestUpdateBuilder;
import com.quickblox.q_municate_core.R;
import com.quickblox.q_municate_core.models.AppSession;
import com.quickblox.q_municate_core.models.CombinationMessage;
import com.quickblox.q_municate_core.models.NotificationType;
import com.quickblox.q_municate_core.service.QBServiceConsts;
import com.quickblox.q_municate_core.utils.ChatNotificationUtils;
import com.quickblox.q_municate_core.utils.ChatUtils;
import com.quickblox.q_municate_core.utils.ConstsCore;
import com.quickblox.q_municate_core.utils.DateUtilsCore;
import com.quickblox.q_municate_core.utils.DbUtils;
import com.quickblox.q_municate_core.utils.FinderUnknownUsers;
import com.quickblox.q_municate_db.managers.DataManager;
import com.quickblox.q_municate_db.models.Attachment;
import com.quickblox.q_municate_db.models.Dialog;
import com.quickblox.q_municate_db.models.DialogNotification;
import com.quickblox.q_municate_db.models.DialogOccupant;
import com.quickblox.q_municate_db.models.Message;
import com.quickblox.q_municate_db.models.State;
import com.quickblox.q_municate_db.models.User;
import com.quickblox.q_municate_db.utils.ErrorUtils;
import com.quickblox.users.model.QBUser;

import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smackx.muc.DiscussionHistory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class QBChatHelper extends BaseHelper {

    private static final String TAG = QBChatHelper.class.getSimpleName();

    protected QBChatService chatService;
    protected QBUser chatCreator;
    protected QBChatDialog currentDialog;
    protected DataManager dataManager;
    protected PrivateChatMessageListener privateChatMessageListener;
    protected GroupChatMessageListener groupChatMessageListener;
    private TypingListener typingListener;
    private PrivateChatMessagesStatusListener privateChatMessagesStatusListener;
    private List<QBNotificationChatListener> notificationChatListeners;
    private final AllChatMessagesListener allChatMessagesListener;
    private QBSystemMessagesManager systemMessagesManager;
    private List<QBChatDialog> groupDialogsList;
    private QBChatDialogParticipantListener participantListener;



    public QBChatHelper(Context context) {
        super(context);
        participantListener = new ParticipantListener();


        privateChatMessageListener = new PrivateChatMessageListener();
        allChatMessagesListener = new AllChatMessagesListener();
        typingListener = new TypingListener();
        privateChatMessagesStatusListener = new PrivateChatMessagesStatusListener();

        groupChatMessageListener = new GroupChatMessageListener();

        notificationChatListeners = new CopyOnWriteArrayList<>();

        QBNotificationChatListener notificationChatListener = new PrivateChatNotificationListener();
        addNotificationChatListener(notificationChatListener);

        dataManager = DataManager.getInstance();
    }

    public synchronized QBChatDialog createChatLocally(QBChatDialog dialog, Bundle additional) throws QBResponseException {
        currentDialog = dialog;
        currentDialog.initForChat(chatService);
        if (QBDialogType.GROUP.equals(dialog.getType())) {
            currentDialog.addParticipantListener(participantListener);
        }
        return dialog;
    }

    public void closeChat(QBChatDialog dialogId, Bundle additional){
        if (currentDialog != null && currentDialog.getDialogId().equals(dialogId)) {
            currentDialog = null;
        }
    }

    public void init(QBUser chatCreator) {
        this.chatService = QBChatService.getInstance();
        this.chatCreator = chatCreator;

        chatService.getMessageStatusesManager().addMessageStatusListener(privateChatMessagesStatusListener);
        chatService.getIncomingMessagesManager().addDialogMessageListener(allChatMessagesListener);

        systemMessagesManager = QBChatService.getInstance().getSystemMessagesManager();

        addSystemMessageListener(new SystemMessageListener());
    }

    protected void addSystemMessageListener(QBSystemMessageListener systemMessageListener) {
        systemMessagesManager.addSystemMessageListener(systemMessageListener);
    }

    protected void addNotificationChatListener(QBNotificationChatListener notificationChatListener) {
        notificationChatListeners.add(notificationChatListener);
    }

    public void sendChatMessage(String message, QBChatDialog chatDialog) throws QBResponseException {
        sendChatMessage(null, message, chatDialog);
    }

    public void sendMessageWithAttachImage(QBFile file, QBChatDialog chatDialog) throws QBResponseException {
        sendChatMessage(file, context.getString(R.string.dlg_attached_last_message), chatDialog);
    }

    private void sendChatMessage(QBFile file, String message, QBChatDialog chatDialog) throws QBResponseException {
        QBChatMessage qbChatMessage = getQBChatMessage(message, file);
        sendAndSaveChatMessage(qbChatMessage, chatDialog);
    }

    public void sendAndSaveChatMessage(QBChatMessage qbChatMessage, QBChatDialog chatDialog) throws QBResponseException {
        addNecessaryPropertyForQBChatMessage(qbChatMessage, chatDialog.getDialogId());

        sendChatMessage(qbChatMessage, chatDialog);
        DbUtils.saveMessageOrNotificationToCache(context, dataManager, chatDialog.getDialogId(), qbChatMessage, null, true);
        DbUtils.updateDialogModifiedDate(dataManager, chatDialog.getDialogId(), ChatUtils.getMessageDateSent(qbChatMessage),
                true);
    }

    public void sendChatMessage(QBChatMessage message, QBChatDialog chatDialog) throws QBResponseException {
        message.setMarkable(true);
        chatDialog.initForChat(chatService);

        String error = null;
        try {
            chatDialog.sendMessage(message);
        } catch (SmackException.NotConnectedException e) {
            error = context.getString(R.string.dlg_fail_connection);
        }
        if (error != null) {
            throw new QBResponseException(error);
        }
    }

    public void sendSystemMessage(QBChatMessage chatMessage, int opponentId, String dialogId) {
        addNecessaryPropertyForQBChatMessage(chatMessage, dialogId);
        chatMessage.setRecipientId(opponentId);
        try {
            systemMessagesManager.sendSystemMessage(chatMessage);
        } catch (SmackException.NotConnectedException e) {
            ErrorUtils.logError(e);
        }
    }

    protected void addNecessaryPropertyForQBChatMessage(QBChatMessage qbChatMessage, String dialogId) {
        long time = DateUtilsCore.getCurrentTime();
        qbChatMessage.setDialogId(dialogId);
        qbChatMessage.setProperty(ChatNotificationUtils.PROPERTY_DATE_SENT, time + ConstsCore.EMPTY_STRING);
    }

    public List<QBChatDialog> getDialogs(QBRequestGetBuilder qbRequestGetBuilder, Bundle returnedBundle) throws QBResponseException {
        List<QBChatDialog> qbDialogsList = QBRestChatService.getChatDialogs(null, qbRequestGetBuilder).perform();

        if (qbDialogsList != null && !qbDialogsList.isEmpty()) {
            FinderUnknownUsers finderUnknownUsers = new FinderUnknownUsers(context, AppSession.getSession().getUser(), qbDialogsList);
            finderUnknownUsers.find();
            DbUtils.saveDialogsToCache(dataManager, qbDialogsList, currentDialog);
            DbUtils.updateDialogsOccupantsStatusesIfNeeded(dataManager, qbDialogsList);
        }

        return qbDialogsList;
    }

    public List<QBChatMessage> getDialogMessages(QBRequestGetBuilder customObjectRequestBuilder,
                                                        Bundle returnedBundle, QBChatDialog qbDialog,
                                                        long lastDateLoad) throws QBResponseException {
        List<QBChatMessage> qbMessagesList = QBRestChatService.getDialogMessages(qbDialog,
                customObjectRequestBuilder).perform();

        if (qbMessagesList != null && !qbMessagesList.isEmpty()) {
            DbUtils.saveMessagesToCache(context, dataManager, qbMessagesList, qbDialog.getDialogId());
        }

        return qbMessagesList;
    }

    public void deleteDialog(String dialogId) {
        try {
            QBRestChatService.deleteDialog(dialogId, false).perform();
        } catch (QBResponseException e) {
            ErrorUtils.logError(e);
        }

        DbUtils.deleteDialogLocal(dataManager, dialogId);
    }

    protected QBChatMessage getQBChatMessage(String body, QBFile qbFile) {
        long time = DateUtilsCore.getCurrentTime();
        QBChatMessage chatMessage = new QBChatMessage();
        chatMessage.setBody(body);

        if (qbFile != null) {
            QBAttachment attachment = getAttachment(qbFile);
            chatMessage.addAttachment(attachment);
        }

        chatMessage.setProperty(ChatNotificationUtils.PROPERTY_DATE_SENT, time + ConstsCore.EMPTY_STRING);
        chatMessage.setSaveToHistory(ChatNotificationUtils.VALUE_SAVE_TO_HISTORY);

        return chatMessage;
    }

    private QBAttachment getAttachment(QBFile file) {
        // TODO temp value
        String contentType = "image/jpeg";

        QBAttachment attachment = new QBAttachment(QBAttachment.PHOTO_TYPE);
        attachment.setId(file.getUid());
        attachment.setName(file.getName());
        attachment.setContentType(contentType);
        attachment.setUrl(file.getPublicUrl());
        attachment.setSize(file.getSize());

        return attachment;
    }

    public void sendTypingStatusToServer(String dialogId, boolean startTyping)  {
        try {
            QBChatDialog chatDialog = createPrivateChatIfNotExist(dialogId);
            if (startTyping) {
                chatDialog.sendIsTypingNotification();
            } else {
                chatDialog.sendStopTypingNotification();
            }
        } catch (XMPPException | SmackException.NotConnectedException | QBResponseException e) {
            ErrorUtils.logError(e);
        }
    }

    public QBChatDialog createPrivateChatIfNotExist(String dialogId) throws QBResponseException {
        if (!chatService.isLoggedIn()) {
            ErrorUtils.logError(TAG, " not logged to the chat");
            throw new QBResponseException(context.getString(R.string.dlg_fail_create_chat));
        }

        Dialog tempDialog = dataManager.getDialogDataManager().getByDialogId(dialogId);
        QBChatDialog chatDialog = ChatUtils.createQBDialogFromLocalDialog(dataManager, tempDialog);

        chatDialog.initForChat(chatService);

        return chatDialog;
    }

    public QBChatDialog createPrivateChatOnRest(int opponentId) throws QBResponseException {
        QBChatDialog dialog = QBRestChatService.createChatDialog(DialogUtils.buildPrivateDialog(opponentId)).perform();
        return dialog;
    }

    public QBChatDialog createPrivateDialogIfNotExist(int userId) throws QBResponseException {
        QBChatDialog existingPrivateDialog = ChatUtils.getExistPrivateDialog(dataManager, userId);
        if (existingPrivateDialog == null) {
            existingPrivateDialog = createPrivateChatOnRest(userId);
            DbUtils.saveDialogToCache(dataManager, existingPrivateDialog);
        }
        return existingPrivateDialog;
    }

    protected void checkForSendingNotification(boolean ownMessage, QBChatMessage qbChatMessage, User user,
            boolean isPrivateChat) {
        String dialogId = qbChatMessage.getDialogId();
        if (qbChatMessage.getId() == null || dialogId == null) {
            return;
        }

        sendNotificationBroadcast(QBServiceConsts.GOT_CHAT_MESSAGE, qbChatMessage, user, dialogId,
                isPrivateChat);

        if (currentDialog != null) {
            if (!ownMessage && !currentDialog.getDialogId().equals(dialogId)) {
                sendNotificationBroadcast(QBServiceConsts.GOT_CHAT_MESSAGE_LOCAL, qbChatMessage, user, dialogId, isPrivateChat);
            }
        } else {
            sendNotificationBroadcast(QBServiceConsts.GOT_CHAT_MESSAGE_LOCAL, qbChatMessage, user, dialogId,
                    isPrivateChat);
        }
    }

    private void sendNotificationBroadcast(String action, QBChatMessage chatMessage, User user, String dialogId,
            boolean isPrivateMessage) {
        Intent intent = new Intent(action);
        String messageBody = chatMessage.getBody();
        String extraChatMessage;

        if (chatMessage.getAttachments() != null && !chatMessage.getAttachments().isEmpty()) {
            extraChatMessage = context.getResources().getString(R.string.file_was_attached);
        } else {
            extraChatMessage = messageBody;
        }

        intent.putExtra(QBServiceConsts.EXTRA_CHAT_MESSAGE, extraChatMessage);
        intent.putExtra(QBServiceConsts.EXTRA_USER, user);
        intent.putExtra(QBServiceConsts.EXTRA_DIALOG_ID, dialogId);
        intent.putExtra(QBServiceConsts.EXTRA_IS_PRIVATE_MESSAGE, isPrivateMessage);

        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        context.sendBroadcast(intent);
    }

    protected void notifyMessageTyping(int userId, boolean isTyping) {
        Intent intent = new Intent(QBServiceConsts.TYPING_MESSAGE);
        intent.putExtra(QBServiceConsts.EXTRA_USER_ID, userId);
        intent.putExtra(QBServiceConsts.EXTRA_IS_TYPING, isTyping);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    protected Message parseReceivedMessage(QBChatMessage qbChatMessage) {
        long dateSent = ChatUtils.getMessageDateSent(qbChatMessage);
        String attachUrl = ChatUtils.getAttachUrlIfExists(qbChatMessage);
        String dialogId = qbChatMessage.getDialogId();

        Message message = new Message();
        message.setMessageId(qbChatMessage.getId());
        message.setBody(qbChatMessage.getBody());
        message.setCreatedDate(dateSent);
        message.setState(State.DELIVERED);

        DialogOccupant dialogOccupant = dataManager.getDialogOccupantDataManager().getDialogOccupant(dialogId, qbChatMessage.getSenderId());
        if (dialogOccupant == null) {
            dialogOccupant = new DialogOccupant();
            Dialog dialog = dataManager.getDialogDataManager().getByDialogId(dialogId);
            if (dialog != null) {
                dialogOccupant.setDialog(dialog);
            }
            User user = dataManager.getUserDataManager().get(qbChatMessage.getSenderId());
            if (user != null) {
                dialogOccupant.setUser(user);
            }
        }

        message.setDialogOccupant(dialogOccupant);

        if (qbChatMessage.getAttachments()!= null && !qbChatMessage.getAttachments().isEmpty()) {
            Attachment attachment = new Attachment();
            attachment.setType(Attachment.Type.PICTURE);
            attachment.setRemoteUrl(attachUrl);
            message.setAttachment(attachment);
        }

        return message;
    }

    public void updateStatusNotificationMessageRead(String dialogId, CombinationMessage combinationMessage) throws Exception {
        updateStatusMessageReadServer(dialogId, combinationMessage, false);
        DbUtils.updateStatusNotificationMessageLocal(dataManager, combinationMessage.toDialogNotification());
    }

    public void updateStatusMessageRead(String dialogId, CombinationMessage combinationMessage,
            boolean forPrivate) throws Exception {
        updateStatusMessageReadServer(dialogId, combinationMessage, forPrivate);
        DbUtils.updateStatusMessageLocal(dataManager, combinationMessage.toMessage());
    }

    public void updateStatusMessageReadServer(String dialogId, CombinationMessage combinationMessage,
            boolean fromPrivate) throws Exception {
        if (fromPrivate) {
            QBChatDialog privateChat = createPrivateChatIfNotExist(dialogId);
            if (privateChat != null) {
                QBChatMessage qbChatMessage = new QBChatMessage();
                qbChatMessage.setId(combinationMessage.getMessageId());
                qbChatMessage.setDialogId(dialogId);
                qbChatMessage.setSenderId(combinationMessage.getDialogOccupant().getUser().getUserId());

                privateChat.readMessage(qbChatMessage);
            }
        } else {
            StringifyArrayList<String> messagesIdsList = new StringifyArrayList<String>();
            messagesIdsList.add(combinationMessage.getMessageId());
            QBRestChatService.markMessagesAsRead(dialogId, messagesIdsList).perform();
        }
    }

    private void createDialogByNotification(QBChatMessage qbChatMessage, DialogNotification.Type notificationType) {
        qbChatMessage.setBody(context.getString(R.string.cht_notification_message));

        QBChatDialog qbDialog = ChatNotificationUtils.parseDialogFromQBMessage(context, qbChatMessage, qbChatMessage.getBody(), QBDialogType.GROUP);

        qbDialog.getOccupants().add(chatCreator.getId());
        DbUtils.saveDialogToCache(dataManager, qbDialog);

        String roomJidId = qbDialog.getRoomJid();
        if (roomJidId != null) {
            tryJoinRoomChat(qbDialog);
            new FinderUnknownUsers(context, chatCreator, qbDialog).find();
        }

        DialogNotification dialogNotification = ChatUtils.convertMessageToDialogNotification(parseReceivedMessage(qbChatMessage));
        dialogNotification.setType(notificationType);
        Message message = ChatUtils.createTempLocalMessage(dialogNotification);
        DbUtils.saveTempMessage(dataManager, message);

        boolean ownMessage = !message.isIncoming(chatCreator.getId());
        User user = DataManager.getInstance().getUserDataManager().get(qbChatMessage.getSenderId());
        checkForSendingNotification(ownMessage, qbChatMessage, user, false);
    }

    public void tryJoinRoomChats() {
        List<Dialog> dialogsList = dataManager.getDialogDataManager().getAll();

        if (dialogsList != null) {
            List<QBChatDialog> qbDialogsList = ChatUtils.createQBDialogsListFromDialogsList(dataManager, dialogsList);
            tryJoinRoomChats(qbDialogsList);
        }
    }

    public void tryJoinRoomChats(List<QBChatDialog> qbDialogsList) {
        tryJoinRoomChatsPage(qbDialogsList, true);
    }

    public synchronized void tryJoinRoomChatsPage(List<QBChatDialog> qbDialogsList, boolean needClean) {
        if (!qbDialogsList.isEmpty()) {
            initGroupDialogsList(needClean);
            for (QBChatDialog dialog : qbDialogsList) {
                if (!QBDialogType.PRIVATE.equals(dialog.getType())) {
                    groupDialogsList.add(dialog);
                    tryJoinRoomChat(dialog);
                }
            }
        }
    }

    private void initGroupDialogsList(boolean needClean) {
        if (groupDialogsList == null) {
            groupDialogsList = new ArrayList<>();
        } else {
            if (needClean) {
                groupDialogsList.clear();
            }
        }
    }

    public void tryJoinRoomChat(QBChatDialog dialog) {
        try {
            joinRoomChat(dialog);
        } catch (Exception e) {
            ErrorUtils.logError(e);
        }
    }

    public void joinRoomChat(QBChatDialog dialog) throws Exception {
        if (!dialog.isJoined()) {
            DiscussionHistory history = new DiscussionHistory();
            history.setMaxStanzas(0); // without getting messages
            dialog.join(history);
        }
    }

    public QBChatDialog addUsersToDialog(String dialogId, List<Integer> userIdsList) throws Exception {
        StringifyArrayList<Integer> occupantsIdsList = new StringifyArrayList<>(userIdsList);
        QBChatDialog dialog = ChatUtils.createQBDialogFromLocalDialog(dataManager,
                dataManager.getDialogDataManager().getByDialogId(dialogId));

        QBRequestUpdateBuilder requestBuilder = new QBRequestUpdateBuilder();
        requestBuilder.push(com.quickblox.chat.Consts.DIALOG_OCCUPANTS, occupantsIdsList.getItemsAsString());
        return updateDialog(dialog, requestBuilder);
    }

    public void removeUsersFromDialog(Dialog dialog, List<Integer> userIdsList) throws QBResponseException {
        QBRequestUpdateBuilder requestBuilder = new QBRequestUpdateBuilder();
        requestBuilder.pullAll(com.quickblox.chat.Consts.DIALOG_OCCUPANTS, userIdsList.toArray());
        updateDialog(ChatUtils.createQBDialogFromLocalDialog(dataManager, dialog), requestBuilder);
        DataManager.getInstance().getDialogDataManager().delete(dialog);
    }

    public QBChatDialog updateDialog(QBChatDialog dialog) throws QBResponseException {
        return updateDialog(dialog, (QBRequestUpdateBuilder) null);
    }

    public QBChatDialog updateDialog(QBChatDialog dialog, File inputFile) throws QBResponseException {
        QBFile file = QBContent.uploadFileTask(inputFile, true, (String) null).perform();
        dialog.setPhoto(file.getPublicUrl());
        return updateDialog(dialog, (QBRequestUpdateBuilder) null);
    }

    private QBChatDialog updateDialog(QBChatDialog dialog, QBRequestUpdateBuilder requestBuilder) throws QBResponseException {
        QBChatDialog updatedDialog = QBRestChatService.updateGroupChatDialog(dialog, requestBuilder).perform();
        return updatedDialog;
    }

    public QBChatDialog createGroupChat(String name, List<Integer> friendIdsList, String photoUrl) throws Exception {
        ArrayList<Integer> occupantIdsList = (ArrayList<Integer>) ChatUtils.getOccupantIdsWithUser(friendIdsList);

        QBChatDialog dialogToCreate = new QBChatDialog();
        dialogToCreate.setName(name);
        dialogToCreate.setType(QBDialogType.GROUP);
        dialogToCreate.setOccupantsIds(occupantIdsList);
        dialogToCreate.setPhoto(photoUrl);

        QBChatDialog qbDialog = QBRestChatService.createChatDialog(dialogToCreate).perform();
        DbUtils.saveDialogToCache(dataManager, qbDialog);

        joinRoomChat(qbDialog);

        sendSystemMessageAboutCreatingGroupChat(qbDialog, friendIdsList);

        QBChatMessage chatMessage = ChatNotificationUtils.createGroupMessageAboutCreateGroupChat(context, qbDialog, photoUrl);
        sendChatMessage(chatMessage, qbDialog);

        return qbDialog;
    }

    public void sendSystemMessageAboutCreatingGroupChat(QBChatDialog dialog, List<Integer> friendIdsList) throws Exception {
        for (Integer friendId : friendIdsList) {
            try {
                sendSystemMessageAboutCreatingGroupChat(dialog, friendId);
            } catch (QBResponseException e) {
                ErrorUtils.logError(e);
            }
        }
    }

    private void sendSystemMessageAboutCreatingGroupChat(QBChatDialog dialog, Integer friendId) throws Exception {
        QBChatMessage chatMessageForSending = ChatNotificationUtils
                .createSystemMessageAboutCreatingGroupChat(context, dialog);

        addNecessaryPropertyForQBChatMessage(chatMessageForSending, dialog.getDialogId());
        sendSystemMessage(chatMessageForSending, friendId, dialog.getDialogId());
    }

    public void leaveDialogs() throws XMPPException, SmackException.NotConnectedException {
        if (groupDialogsList != null) {
            for (QBChatDialog dialog : groupDialogsList) {
                if (dialog.isJoined()){
                    dialog.leave();
                }
            }
        }
    }

    public void leaveRoomChat(Dialog dialog) throws Exception {
        QBChatDialog chatDialog = ChatUtils.createQBDialogFromLocalDialog(dataManager, dialog);
        //TODO VT maybe need init before leave
        chatDialog.leave();
        List<Integer> userIdsList = new ArrayList<Integer>();
        userIdsList.add(chatCreator.getId());
        removeUsersFromDialog(dialog, userIdsList);
    }

    public boolean isDialogJoined(QBChatDialog dialog) {
//        QBGroupChat roomChat;
        boolean joined = false;
//        try {
//            roomChat = createGroupChatIfNotExist(dialog);
        joined = dialog.isJoined();
//        } catch (QBResponseException e) {
//            ErrorUtils.logError(e);
//        }
        return joined;
    }

    public void sendGroupMessageToFriends(QBChatDialog qbDialog, DialogNotification.Type notificationType,
                                          Collection<Integer> occupantsIdsList, boolean leavedFromDialog) throws QBResponseException {
        QBChatMessage chatMessage = ChatNotificationUtils.createGroupMessageAboutUpdateChat(context, qbDialog,
                notificationType, occupantsIdsList, leavedFromDialog);
        sendChatMessage(chatMessage, qbDialog);
    }

    public QBFile loadAttachFile(File inputFile) throws Exception {
        QBFile file;

        try {
            file = QBContent.uploadFileTask(inputFile, true, null).perform();
        } catch (QBResponseException exc) {
            throw new Exception(context.getString(R.string.dlg_fail_upload_attach));
        }

        return file;
    }

//    public void onGroupMessageReceived(String groupChat, final QBChatMessage chatMessage) {
//    }

    public void onGroupMessageReceived(String dialogId, QBChatMessage qbChatMessage) {
//        User user = DataManager.getInstance().getUserDataManager().get(qbChatMessage.getSenderId());
        Message message = parseReceivedMessage(qbChatMessage);

        boolean ownMessage = !message.isIncoming(chatCreator.getId());

        if (ChatNotificationUtils.isNotificationMessage(qbChatMessage)) {
            DialogNotification dialogNotification = ChatUtils.convertMessageToDialogNotification(message);
            dialogNotification.setType(ChatNotificationUtils.getUpdateChatLocalNotificationType(qbChatMessage));
            dialogNotification.setBody(ChatNotificationUtils.getBodyForUpdateChatNotificationMessage(context, dataManager, qbChatMessage));

            if (!ownMessage) {
                updateGroupDialogByNotification(qbChatMessage);
            }
        }
//
//        //TODO need remove for not own messages
//        DbUtils.saveMessageOrNotificationToCache(context, dataManager, dialogId, qbChatMessage, State.DELIVERED, true);
//        DbUtils.updateDialogModifiedDate(dataManager, dialogId, ChatUtils.getMessageDateSent(qbChatMessage), true);
//        //TODO end
//
//        checkForSendingNotification(ownMessage, qbChatMessage, user, false);
    }

    private void updateGroupDialogByNotification(QBChatMessage qbChatMessage) {
        String dialogId = qbChatMessage.getDialogId();
        Dialog dialog = dataManager.getDialogDataManager().getByDialogId(dialogId);
        QBChatDialog qbDialog;
        if (dialog == null) {
            qbDialog = ChatNotificationUtils.parseDialogFromQBMessage(context, qbChatMessage, QBDialogType.GROUP);
        } else {
            qbDialog = ChatUtils.createQBDialogFromLocalDialog(dataManager, dialog);
        }

        ChatNotificationUtils.updateDialogFromQBMessage(context, dataManager, qbChatMessage, qbDialog);
        DbUtils.saveDialogToCache(dataManager, qbDialog);

        notifyUpdatingDialog();
    }

    protected void notifyUpdatingDialog() {
        Intent intent = new Intent(QBServiceConsts.UPDATE_DIALOG);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

//    public void onPrivateMessageReceived(String privateChat, final QBChatMessage chatMessage) {
//    }

    public void onPrivateMessageReceived(String dialogId, QBChatMessage qbChatMessage) {
//        if (qbChatMessage.getId() != null && dialogId != null) {
//            User user = dataManager.getUserDataManager().get(qbChatMessage.getSenderId());
//            Dialog dialog = dataManager.getDialogDataManager().getByDialogId(dialogId);
//            if (dialog == null) {
//                QBChatDialog qbDialog = ChatNotificationUtils.parseDialogFromQBMessage(context, qbChatMessage, QBDialogType.PRIVATE);
//                ChatUtils.addOccupantsToQBDialog(qbDialog, qbChatMessage);
//                DbUtils.saveDialogToCache(dataManager, qbDialog);
//            }
//            DbUtils.saveMessageOrNotificationToCache(context, dataManager, dialogId, qbChatMessage, State.DELIVERED, true);
//            DbUtils.updateDialogModifiedDate(dataManager, dialogId, ChatUtils.getMessageDateSent(qbChatMessage), true);
//
//            checkForSendingNotification(false, qbChatMessage, user, true);
//        }
    }

    private void onChatMessageReceived(String dialogId, QBChatMessage chatMessage, Integer senderId){
        User user = dataManager.getUserDataManager().get(senderId);
        Dialog dialog = dataManager.getDialogDataManager().getByDialogId(dialogId);
        Message message = parseReceivedMessage(chatMessage);

        boolean ownMessage = !message.isIncoming(chatCreator.getId());
        boolean isPrivateChatMessage = Dialog.Type.PRIVATE.equals(dialog.getType());

        if (ChatNotificationUtils.isNotificationMessage(chatMessage)) {
            if (isNotificationToGroupChat(chatMessage)){
                //TODO VT magic code: dialogNotification not where used
                DialogNotification dialogNotification = ChatUtils.convertMessageToDialogNotification(message);
                dialogNotification.setType(ChatNotificationUtils.getUpdateChatLocalNotificationType(chatMessage));
                dialogNotification.setBody(ChatNotificationUtils.getBodyForUpdateChatNotificationMessage(context, dataManager, chatMessage));
                //TODO end

                if (!ownMessage) {
                    updateGroupDialogByNotification(chatMessage);
                }
            } else {
                for (QBNotificationChatListener notificationChatListener : notificationChatListeners) {
                    notificationChatListener.onReceivedNotification((String) chatMessage.getProperty(
                            ChatNotificationUtils.PROPERTY_NOTIFICATION_TYPE), chatMessage);
                }
            }
        } else if (dialog == null) {
            //TODO VT need return to this code (receive message to dialog which absent)
                QBChatDialog qbDialog = ChatNotificationUtils.parseDialogFromQBMessage(context, chatMessage, QBDialogType.PRIVATE);
                ChatUtils.addOccupantsToQBDialog(qbDialog, chatMessage);
                DbUtils.saveDialogToCache(dataManager, qbDialog);
        }else {
            //TODO need remove for not own messages
            DbUtils.saveMessageOrNotificationToCache(context, dataManager, dialogId, chatMessage, State.DELIVERED, true);
            DbUtils.updateDialogModifiedDate(dataManager, dialogId, ChatUtils.getMessageDateSent(chatMessage), true);
            //TODO end

            checkForSendingNotification(ownMessage, chatMessage, user, isPrivateChatMessage);
        }
    }

    private boolean isNotificationToGroupChat(QBChatMessage chatMessage) {
        String updatedInfo = (String) chatMessage.getProperty(ChatNotificationUtils.PROPERTY_ROOM_UPDATE_INFO);
        return updatedInfo != null;
    }

    protected void notifyUpdatingDialogDetails(int userId, boolean online) {
        Intent intent = new Intent(QBServiceConsts.UPDATE_DIALOG_DETAILS);
        intent.putExtra(QBServiceConsts.EXTRA_USER_ID, userId);
        intent.putExtra(QBServiceConsts.EXTRA_STATUS, online);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    public interface QBNotificationChatListener {

        void onReceivedNotification(String notificationType, QBChatMessage chatMessage);
    }

    private class GroupChatMessageListener implements QBChatDialogMessageListener {

        @Override
        public void processMessage(String dialogId, QBChatMessage chatMessage, Integer senderId) {
            onGroupMessageReceived(dialogId, chatMessage);
        }

        @Override
        public void processError(String s, QBChatException e, QBChatMessage qbChatMessage, Integer integer) {

        }
    }

    private class PrivateChatMessageListener implements QBChatDialogMessageListener {

        @Override
        public void processMessage(String dialogId, QBChatMessage chatMessage, Integer senderId) {
            if (ChatNotificationUtils.isNotificationMessage(chatMessage)) {
                for (QBNotificationChatListener notificationChatListener : notificationChatListeners) {
                    notificationChatListener.onReceivedNotification((String) chatMessage.getProperty(
                            ChatNotificationUtils.PROPERTY_NOTIFICATION_TYPE), chatMessage);
                }
            } else {
                onPrivateMessageReceived(dialogId, chatMessage);
            }

        }

        @Override
        public void processError(String s, QBChatException e, QBChatMessage qbChatMessage, Integer integer) {

        }
    }

    private class AllChatMessagesListener implements QBChatDialogMessageListener {

        @Override
        public void processMessage(String dialogId, QBChatMessage qbChatMessage, Integer senderId) {
            onChatMessageReceived(dialogId, qbChatMessage, senderId);
        }

        @Override
        public void processError(String dialogId, QBChatException exception, QBChatMessage qbChatMessage, Integer integer) {
            //TODO VT need implement for process error message
        }
    }

    private class PrivateChatMessagesStatusListener implements QBMessageStatusListener {

        @Override
        public void processMessageDelivered(String messageId, String dialogId, Integer userId) {
            DbUtils.updateStatusMessageLocal(dataManager, messageId, State.DELIVERED);
        }

        @Override
        public void processMessageRead(String messageId, String dialogId, Integer userId) {
            DbUtils.updateStatusMessageLocal(dataManager, messageId, State.READ);
        }
    }

    //TODO VT need revrite by new chat logic
    private class PrivateChatManagerListener implements QBPrivateChatManagerListener {

        @Override
        public void chatCreated(QBPrivateChat privateChat, boolean b) {
//            privateChat.addMessageListener(privateChatMessageListener);
//            privateChat.addIsTypingListener(typingListener);
        }
    }

    private class TypingListener implements QBChatDialogTypingListener {

        @Override
        public void processUserIsTyping(String dialogId, Integer userId) {
            notifyMessageTyping(userId, true);
        }

        @Override
        public void processUserStopTyping(String dialogId, Integer userId) {
            notifyMessageTyping(userId, false);
        }
    }

    private class SystemMessageListener implements QBSystemMessageListener {

        @Override
        public void processMessage(QBChatMessage qbChatMessage) {
            String notificationTypeString = (String) qbChatMessage
                    .getProperty(ChatNotificationUtils.PROPERTY_NOTIFICATION_TYPE);
            NotificationType notificationType = NotificationType.parseByValue(
                    Integer.parseInt(notificationTypeString));
            if (NotificationType.GROUP_CHAT_CREATE.equals(notificationType)) {
                createDialogByNotification(qbChatMessage, DialogNotification.Type.CREATE_DIALOG);
            }
        }

        @Override
        public void processError(QBChatException e, QBChatMessage qbChatMessage) {
            ErrorUtils.logError(e);
        }
    }

    private void friendRequestMessageReceived(QBChatMessage qbChatMessage, DialogNotification.Type notificationType) {
        String dialogId = qbChatMessage.getDialogId();
        Message message = parseReceivedMessage(qbChatMessage);

        if (!dataManager.getUserDataManager().exists(qbChatMessage.getSenderId())) {
            QBRestHelper.loadAndSaveUser(qbChatMessage.getSenderId());
        }

        DialogNotification dialogNotification = ChatUtils.convertMessageToDialogNotification(message);
        dialogNotification.setType(notificationType);

        Dialog dialog = dataManager.getDialogDataManager().getByDialogId(dialogId);
        if (dialog == null) {
            QBChatDialog qbDialog = ChatNotificationUtils.parseDialogFromQBMessage(context, qbChatMessage, QBDialogType.PRIVATE);
            ArrayList<Integer> occupantsIdsList = ChatUtils.createOccupantsIdsFromPrivateMessage(chatCreator.getId(), qbChatMessage.getSenderId());
            qbDialog.setOccupantsIds(occupantsIdsList);
            DbUtils.saveDialogToCache(dataManager, qbDialog);
        }

        DialogOccupant dialogOccupant = dataManager.getDialogOccupantDataManager().getDialogOccupant(dialogId, qbChatMessage.getSenderId());
        DbUtils.saveDialogNotificationToCache(context, dataManager, dialogOccupant, qbChatMessage, true);

        checkForSendingNotification(false, qbChatMessage, dialogOccupant.getUser(), true);
    }

    private class PrivateChatNotificationListener implements QBNotificationChatListener {

        @Override
        public void onReceivedNotification(String notificationTypeString, QBChatMessage chatMessage) {
            NotificationType notificationType = NotificationType.parseByValue(
                    Integer.parseInt(notificationTypeString));
            switch (notificationType) {
                case FRIENDS_REQUEST:
                    friendRequestMessageReceived(chatMessage, DialogNotification.Type.FRIENDS_REQUEST);
                    break;
                case FRIENDS_ACCEPT:
                    friendRequestMessageReceived(chatMessage, DialogNotification.Type.FRIENDS_ACCEPT);
                    break;
                case FRIENDS_REJECT:
                    friendRequestMessageReceived(chatMessage, DialogNotification.Type.FRIENDS_REJECT);
                    break;
                case FRIENDS_REMOVE:
                    friendRequestMessageReceived(chatMessage, DialogNotification.Type.FRIENDS_REMOVE);
                    clearFriendOrUserRequestLocal(chatMessage.getSenderId());
                    break;
            }
        }

        private void clearFriendOrUserRequestLocal(int userId) {
            boolean friend = dataManager.getFriendDataManager().getByUserId(userId) != null;
            boolean outgoingUserRequest = dataManager.getUserRequestDataManager().existsByUserId(userId);
            if (friend) {
                dataManager.getFriendDataManager().deleteByUserId(userId);
            } else if (outgoingUserRequest) {
                dataManager.getUserRequestDataManager().deleteByUserId(userId);
            }
        }
    }

    private class ParticipantListener implements QBChatDialogParticipantListener {

        @Override
        public void processPresence(String dialogId, QBPresence presence) {
            boolean validData = currentDialog != null && presence.getUserId() != null;
            if (validData && currentDialog.getRoomJid().equals(JIDHelper.INSTANCE.getRoomJidByDialogId(dialogId))) {
                notifyUpdatingDialogDetails(presence.getUserId(), QBPresence.Type.online.equals(presence.getType()));
            }
        }
    }


}