package com.tencent.liteav.trtcvoiceroom.ui.room;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;

import com.blankj.utilcode.constant.PermissionConstants;
import com.blankj.utilcode.util.PermissionUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.tencent.liteav.trtcvoiceroom.R;
import com.tencent.liteav.trtcvoiceroom.model.TRTCVoiceRoomCallback;
import com.tencent.liteav.trtcvoiceroom.model.TRTCVoiceRoomDef;
import com.tencent.liteav.trtcvoiceroom.ui.base.EarMonitorInstance;
import com.tencent.liteav.trtcvoiceroom.ui.base.VoiceRoomSeatEntity;
import com.tencent.liteav.trtcvoiceroom.ui.widget.CommonBottomDialog;
import com.tencent.liteav.trtcvoiceroom.ui.widget.ConfirmDialogFragment;
import com.tencent.trtc.TRTCCloudDef;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 观众界面
 *
 * @author guanyifeng
 */
public class VoiceRoomAudienceActivity extends VoiceRoomBaseActivity {
    private static final int MSG_DISMISS_LOADING = 1001;
    private Map<String, Integer> mInvitationSeatMap;
    private String               mOwnerId;
    private boolean              mIsSeatInitSuccess;
    private boolean              mIsTakingSeat; //正在进行上麦

    public static void enterRoom(Context context, int roomId, String userId, int audioQuality) {
        Intent starter = new Intent(context, VoiceRoomAudienceActivity.class);
        starter.putExtra(VOICEROOM_ROOM_ID, roomId);
        starter.putExtra(VOICEROOM_USER_ID, userId);
        starter.putExtra(VOICEROOM_AUDIO_QUALITY, audioQuality);
        context.startActivity(starter);
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_DISMISS_LOADING) {
                mHandler.removeMessages(MSG_DISMISS_LOADING);
                mProgressBar.setVisibility(View.GONE);
                mIsTakingSeat = false;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initAudience();
    }

    private void initAudience() {
        mInvitationSeatMap = new HashMap<>();
        mVoiceRoomSeatAdapter.setEmptyText(getString(R.string.trtcvoiceroom_msg_click_to_chat));
        mVoiceRoomSeatAdapter.notifyDataSetChanged();
        // 开始进房哦
        enterRoom();
        mBtnMsg.setActivated(true);
        mBtnMsg.setSelected(true);
        refreshView(null);
        mBtnLeaveSeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                leaveSeat();
            }
        });
    }

    private void refreshView(String userId) {
        boolean isMute;
        if (userId == null || mSeatUserMuteMap == null || mSeatUserMuteMap.get(userId) == null) {
            isMute = false;
        } else {
            isMute = mSeatUserMuteMap.get(userId);
        }
        if (mCurrentRole == TRTCCloudDef.TRTCRoleAnchor) {
            mBtnMic.setVisibility(View.VISIBLE);
            mBtnLeaveSeat.setVisibility(View.VISIBLE);
            mBtnMic.setActivated(!isMute);
            mBtnMic.setSelected(!isMute);
            mBtnEffect.setVisibility(View.VISIBLE);
            mAnchorAudioPanel.hideManagerView();
        } else {
            mBtnLeaveSeat.setVisibility(View.GONE);
            mBtnMic.setVisibility(View.GONE);
            mBtnEffect.setVisibility(View.GONE);
        }
    }

    @Override
    public void onBackPressed() {
        showExitRoom();
    }

    private void showExitRoom() {
        if (mConfirmDialogFragment.isAdded()) {
            mConfirmDialogFragment.dismiss();
        }
        mConfirmDialogFragment.setMessage(getString(R.string.trtcvoiceroom_audience_leave_room));
        mConfirmDialogFragment.setNegativeClickListener(new ConfirmDialogFragment.NegativeClickListener() {
            @Override
            public void onClick() {
                mConfirmDialogFragment.dismiss();
            }
        });
        mConfirmDialogFragment.setPositiveClickListener(new ConfirmDialogFragment.PositiveClickListener() {
            @Override
            public void onClick() {
                mConfirmDialogFragment.dismiss();
                exitRoom();
                finish();
            }
        });
        mConfirmDialogFragment.show(getFragmentManager(), "confirm_fragment");
    }

    private void exitRoom() {
        mTRTCVoiceRoom.exitRoom(new TRTCVoiceRoomCallback.ActionCallback() {
            @Override
            public void onCallback(int code, String msg) {
                ToastUtils.showShort(R.string.trtcvoiceroom_toast_exit_the_room_successfully);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void enterRoom() {
        mIsSeatInitSuccess = false;
        mCurrentRole = TRTCCloudDef.TRTCRoleAudience;
        mTRTCVoiceRoom.setSelfProfile(mUserName, mUserAvatar, null);
        mTRTCVoiceRoom.enterRoom(mRoomId, new TRTCVoiceRoomCallback.ActionCallback() {
            @Override
            public void onCallback(int code, String msg) {
                if (code == 0) {
                    //进房成功
                    ToastUtils.showShort(R.string.trtcvoiceroom_toast_enter_the_room_successfully);
                    mTRTCVoiceRoom.setAudioQuality(mAudioQuality);
                } else {
                    ToastUtils.showShort(getString(R.string.trtcvoiceroom_toast_enter_the_room_failure, code, msg));
                    finish();
                }
            }
        });
    }

    @Override
    public void onSeatListChange(List<TRTCVoiceRoomDef.SeatInfo> seatInfoList) {
        super.onSeatListChange(seatInfoList);
        mIsSeatInitSuccess = true;
    }

    /**
     * 点击麦位列表观众端的操作
     *
     * @param itemPos
     */
    @Override
    public void onItemClick(final int itemPos) {
        if (!mIsSeatInitSuccess) {
            ToastUtils.showLong(R.string.trtcvoiceroom_toast_list_has_not_been_initialized);
            return;
        }
        // 判断座位有没有人
        VoiceRoomSeatEntity entity = mVoiceRoomSeatEntityList.get(itemPos);
        if (entity.isClose) {
            ToastUtils.showShort(R.string.trtcvoiceroom_toast_position_is_locked_cannot_apply_for_chat);
        } else if (!entity.isUsed) {
            // 没人弹出申请上麦
            final CommonBottomDialog dialog = new CommonBottomDialog(this);
            dialog.setButton(new CommonBottomDialog.OnButtonClickListener() {
                @Override
                public void onClick(int position, String text) {
                    if (position == 0) {
                        // 发送请求之前再次判断一下这个座位有没有人
                        VoiceRoomSeatEntity seatEntity = mVoiceRoomSeatEntityList.get(itemPos);
                        if (seatEntity.isUsed) {
                            ToastUtils.showShort(R.string.trtcvoiceroom_toast_position_is_already_occupied);
                            return;
                        }
                        if (seatEntity.isClose) {
                            ToastUtils.showShort(getString(R.string.trtcvoiceroom_seat_closed));
                            return;
                        }
                        PermissionUtils.permission(PermissionConstants.MICROPHONE).callback(new PermissionUtils.FullCallback() {
                            @Override
                            public void onGranted(List<String> permissionsGranted) {
                                if (mCurrentRole == TRTCCloudDef.TRTCRoleAnchor) {
                                    startMoveSeat(itemPos);
                                } else {
                                    startTakeSeat(itemPos);
                                }
                            }

                            @Override
                            public void onDenied(List<String> permissionsDeniedForever, List<String> permissionsDenied) {
                                ToastUtils.showShort(R.string.trtcvoiceroom_tips_open_audio);
                            }
                        }).request();

                    }
                    dialog.dismiss();
                }
            }, getString(mCurrentRole == TRTCCloudDef.TRTCRoleAnchor ? R.string.trtcvoiceroom_request_move_seat : R.string.trtcvoiceroom_tv_apply_for_chat));
            dialog.show();
        }
    }

    private void leaveSeat() {
        EarMonitorInstance monitorInstance = EarMonitorInstance.getInstance();
        if (monitorInstance.ismEarMonitorOpen()) {
            EarMonitorInstance.getInstance().updateEarMonitorState(false);
            mTRTCVoiceRoom.setVoiceEarMonitorEnable(false);
        }
        mTRTCVoiceRoom.leaveSeat(new TRTCVoiceRoomCallback.ActionCallback() {
            @Override
            public void onCallback(int code, String msg) {
                if (code == 0) {
                    ToastUtils.showShort(R.string.trtcvoiceroom_toast_offline_successfully);
                } else {
                    ToastUtils.showShort(getString(R.string.trtcvoiceroom_toast_offline_failure, msg));
                }
            }
        });
    }

    private void startTakeSeat(int itemPos) {
        if (mCurrentRole == TRTCCloudDef.TRTCRoleAnchor) {
            ToastUtils.showShort(R.string.trtcvoiceroom_toast_you_are_already_an_anchor);
            return;
        }
        if (mNeedRequest) {
            //需要申请上麦
            if (mOwnerId == null) {
                ToastUtils.showShort(R.string.trtcvoiceroom_toast_the_room_is_not_ready);
                return;
            }
            String inviteId = mTRTCVoiceRoom.sendInvitation(TCConstants.CMD_REQUEST_TAKE_SEAT, mOwnerId, String.valueOf(changeSeatIndexToModelIndex(itemPos)), new TRTCVoiceRoomCallback.ActionCallback() {
                @Override
                public void onCallback(int code, String msg) {
                    if (code == 0) {
                        ToastUtils.showShort(R.string.trtcvoiceroom_toast_application_has_been_sent_please_wait_for_processing);
                    } else {
                        ToastUtils.showShort(getString(R.string.trtcvoiceroom_toast_failed_to_send_application, msg));
                    }
                }
            });
            mInvitationSeatMap.put(inviteId, itemPos);
        } else {
            //不需要的情况下自动上麦
            if (mIsTakingSeat) {
                return;
            }
            showTakingSeatLoading(true);
            mTRTCVoiceRoom.enterSeat(changeSeatIndexToModelIndex(itemPos), new TRTCVoiceRoomCallback.ActionCallback() {
                @Override
                public void onCallback(int code, String msg) {
                    if (code != 0) {
                        showTakingSeatLoading(false);
                    }
                }
            });
        }
    }

    private void startMoveSeat(int itemPos) {
        if (mCurrentRole != TRTCCloudDef.TRTCRoleAnchor) {
            return;
        }
        if (mNeedRequest) {
            //需要申请上麦
            if (mOwnerId == null) {
                ToastUtils.showShort(R.string.trtcvoiceroom_toast_the_room_is_not_ready);
                return;
            }
            String inviteId = mTRTCVoiceRoom.sendInvitation(TCConstants.CMD_REQUEST_TAKE_SEAT, mOwnerId, String.valueOf(changeSeatIndexToModelIndex(itemPos)), new TRTCVoiceRoomCallback.ActionCallback() {
                @Override
                public void onCallback(int code, String msg) {
                    if (code == 0) {
                        ToastUtils.showShort(R.string.trtcvoiceroom_toast_application_has_been_sent_please_wait_for_processing);
                    } else {
                        ToastUtils.showShort(getString(R.string.trtcvoiceroom_toast_failed_to_send_application, msg));
                    }
                }
            });
            mInvitationSeatMap.put(inviteId, itemPos);
        } else {
            //不需要的情况下自动上麦
            if (mIsTakingSeat) {
                return;
            }
            showTakingSeatLoading(true);
            mTRTCVoiceRoom.moveSeat(changeSeatIndexToModelIndex(itemPos), new TRTCVoiceRoomCallback.ActionCallback() {
                @Override
                public void onCallback(int code, String msg) {
                    if (code != 0) {
                        showTakingSeatLoading(false);
                    }
                }
            });
        }
    }

    private void showTakingSeatLoading(boolean isShow) {
        mIsTakingSeat = isShow;
        mProgressBar.setVisibility(isShow ? View.VISIBLE : View.GONE);
        if (isShow) {
            mHandler.sendEmptyMessageDelayed(MSG_DISMISS_LOADING, 10000);
        } else {
            mHandler.removeMessages(MSG_DISMISS_LOADING);
        }
    }

    private void recvPickSeat(final String id, String cmd, final String content) {
        //这里收到了主播抱麦的邀请
        if (mConfirmDialogFragment != null && mConfirmDialogFragment.isAdded()) {
            mConfirmDialogFragment.dismiss();
        }
        mConfirmDialogFragment = new ConfirmDialogFragment();
        int seatIndex = Integer.parseInt(content);
        mConfirmDialogFragment.setMessage(getString(R.string.trtcvoiceroom_msg_invite_you_to_chat, seatIndex));
        mConfirmDialogFragment.setNegativeClickListener(new ConfirmDialogFragment.NegativeClickListener() {
            @Override
            public void onClick() {
                mTRTCVoiceRoom.rejectInvitation(id, new TRTCVoiceRoomCallback.ActionCallback() {
                    @Override
                    public void onCallback(int code, String msg) {
                        Log.d(TAG, "rejectInvitation callback:" + code);
                        ToastUtils.showShort(R.string.trtcvoiceroom_msg_you_refuse_to_chat);
                    }
                });
                mConfirmDialogFragment.dismiss();
            }
        });
        mConfirmDialogFragment.setPositiveClickListener(new ConfirmDialogFragment.PositiveClickListener() {
            @Override
            public void onClick() {
                //同意上麦，回复接受
                mTRTCVoiceRoom.acceptInvitation(id, new TRTCVoiceRoomCallback.ActionCallback() {
                    @Override
                    public void onCallback(int code, String msg) {
                        if (code != 0) {
                            ToastUtils.showShort(getString(R.string.trtcvoiceroom_toast_accept_request_failure, code));
                        }
                        Log.d(TAG, "acceptInvitation callback:" + code);
                    }
                });
                mConfirmDialogFragment.dismiss();
            }
        });
        mConfirmDialogFragment.show(getFragmentManager(), "confirm_fragment" + seatIndex);
    }

    @Override
    public void onRoomInfoChange(TRTCVoiceRoomDef.RoomInfo roomInfo) {
        super.onRoomInfoChange(roomInfo);
        mOwnerId = roomInfo.ownerId;
    }

    @Override
    public void onReceiveNewInvitation(final String id, String inviter, String cmd, final String content) {
        super.onReceiveNewInvitation(id, inviter, cmd, content);
        if (cmd.equals(TCConstants.CMD_PICK_UP_SEAT)) {
            recvPickSeat(id, cmd, content);
        }
    }

    @Override
    public void onInviteeAccepted(String id, String invitee) {
        super.onInviteeAccepted(id, invitee);
        Integer seatIndex = mInvitationSeatMap.remove(id);
        if (seatIndex != null) {
            VoiceRoomSeatEntity entity = mVoiceRoomSeatEntityList.get(seatIndex);
            if (!entity.isUsed) {
                if (mIsTakingSeat) {
                    return;
                }
                showTakingSeatLoading(true);
                if (mCurrentRole == TRTCCloudDef.TRTCRoleAnchor) {
                    mTRTCVoiceRoom.moveSeat(changeSeatIndexToModelIndex(seatIndex), new TRTCVoiceRoomCallback.ActionCallback() {
                        @Override
                        public void onCallback(int code, String msg) {
                            if (code != 0) {
                                showTakingSeatLoading(false);
                            }
                        }
                    });
                } else {
                    mTRTCVoiceRoom.enterSeat(changeSeatIndexToModelIndex(seatIndex), new TRTCVoiceRoomCallback.ActionCallback() {
                        @Override
                        public void onCallback(int code, String msg) {
                            if (code != 0) {
                                showTakingSeatLoading(false);
                            }
                        }
                    });
                }


            }
        }
    }

    @Override
    public void onSeatMute(int index, boolean isMute) {
        super.onSeatMute(index, isMute);
    }

    @Override
    public void onAnchorEnterSeat(int index, TRTCVoiceRoomDef.UserInfo user) {
        super.onAnchorEnterSeat(index, user);
        if (user.userId.equals(mSelfUserId)) {
            showTakingSeatLoading(false);
            mCurrentRole = TRTCCloudDef.TRTCRoleAnchor;
            refreshView(user.userId);
        }
    }

    @Override
    public void onAnchorLeaveSeat(int index, TRTCVoiceRoomDef.UserInfo user) {
        super.onAnchorLeaveSeat(index, user);
        String userId = user.userId;
        if (userId.equals(mSelfUserId) && !isInSeat(userId)) {
            mCurrentRole = TRTCCloudDef.TRTCRoleAudience;
            if (mAnchorAudioPanel != null) {
                mAnchorAudioPanel.reset();
            }
            refreshView(user.userId);
        }
    }

    @Override
    public void onRoomDestroy(String roomId) {
        super.onRoomDestroy(roomId);
        ToastUtils.showLong(R.string.trtcvoiceroom_msg_close_room);
        mTRTCVoiceRoom.exitRoom(null);
        finish();
    }
}
