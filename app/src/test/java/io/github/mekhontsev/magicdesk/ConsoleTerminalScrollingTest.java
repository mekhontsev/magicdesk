package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Runs the View's input handlers with a bounded transcript and recorded terminal input. */
public final class ConsoleTerminalScrollingTest {
    @Test public void scrollCadenceEndsWithTheGestureButNotBeforeItsFling() throws Exception {
        verify("""
                View view=new View(); view.mSession.emulator.tracking=true;
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,50));
                check(view.mRegionScroll.inputs==1 && view.mRegionScroll.continuous,
                        "touch commands did not publish cadence");
                view.startFling(touch(MotionEvent.ACTION_UP,50),1000);
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,50));
                check(view.mRegionScroll.ends==0, "release discarded active fling cadence");
                view.stopFling();
                check(view.mRegionScroll.ends==1, "completed fling retained cadence");
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,50));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,50));
                check(view.mRegionScroll.ends==2, "drag release retained slow presentation");
                MotionEvent wheel=mouse(MotionEvent.ACTION_SCROLL,20); wheel.wheel=1;
                view.onGenericMotionEvent(wheel);
                check(!view.mRegionScroll.continuous, "wheel inherited old touch gesture state");
                """);
    }

    @Test public void flingContinuesAfterReleaseAndStopsAtHistoryEdges() throws Exception {
        verify("""
                View view=new View(); view.mTouchScrolling=true;
                check(view.startFling(touch(MotionEvent.ACTION_UP,50),1000), "finger fling rejected");
                check(view.mScroller.velocity==-1000, "fling direction does not follow finger");
                view.mScroller.y=-45; view.computeScroll();
                check(view.mViewport.topRow()==-5 && view.mViewport.rowOffset()==5 && view.mFlingEvent!=null,
                        "release did not continue scrolling between rows");
                view.mScroller.y=-50; view.computeScroll();
                check(view.mViewport.topRow()==-5, "fractional row lost between animation frames");
                MotionEvent event=view.mFlingEvent;
                view.mScroller.y=-1000; view.computeScroll();
                check(view.mViewport.topRow()==-20 && view.mFlingEvent==null && event.recycled, "history edge did not stop fling");
                int frames=view.animationFrames; view.computeScroll();
                check(view.animationFrames==frames, "idle terminal schedules animation frames");
                view.startFling(touch(MotionEvent.ACTION_UP,10),-1000);
                view.mScroller.y=1000; view.computeScroll();
                check(view.mViewport.topRow()==0 && view.mFlingEvent==null, "fling passed live output");
                """);
    }

    @Test public void flingUsesTheSameTmuxAndAlternateScreenInputRoutesAsDragging() throws Exception {
        verify("""
                View view=new View(); view.mSession.emulator.tracking=true; view.mTouchScrolling=true;
                view.startFling(touch(MotionEvent.ACTION_UP,50),1000);
                view.mScroller.y=-30; view.computeScroll();
                check(view.mSession.emulator.events.equals(List.of("64:true","64:true","64:true"))
                        && view.mViewport.topRow()==0, "tmux fling did not send wheel events");
                view.stopFling(); view.mSession.emulator.tracking=false; view.mSession.emulator.alternate=true;
                view.startFling(touch(MotionEvent.ACTION_UP,50),-1000);
                view.mScroller.y=20; view.computeScroll();
                check(view.mSession.output.equals("downdown"), "alternate screen fling did not use arrow keys");
                view.stopFling(); view.mSession.emulator.alternate=false; view.mSession.emulator.tracking=true;
                view.mSession.emulator.events.clear(); MotionEvent shifted=touch(MotionEvent.ACTION_UP,50);
                shifted.shift=true; view.startFling(shifted,1000); view.mScroller.y=-30; view.computeScroll();
                check(view.mViewport.topRow()==-3 && view.mSession.emulator.events.isEmpty(), "Shift fling escaped local history");
                """);
    }

    @Test public void touchingMovingContentStopsItWithoutClickingOrOpeningKeyboard() throws Exception {
        verify("""
                View view=new View(); view.mSession.emulator.tracking=true; view.mTouchScrolling=true;
                view.startFling(touch(MotionEvent.ACTION_UP,50),1000); MotionEvent event=view.mFlingEvent;
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,30));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,30));
                check(view.mFlingEvent==null && event.recycled, "new touch retained animation");
                check(view.mSession.emulator.events.isEmpty() && view.keyboardRequests==0, "stopping fling became click");
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,30));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,30));
                check(view.mSession.emulator.events.equals(List.of("0:true","0:false")), "stopping fling blocked next tap");
                """);
    }

    @Test public void flingDoesNotStartForSelectionPinchMouseOrSlowRelease() throws Exception {
        verify("""
                View view=new View(); MotionEvent event=touch(MotionEvent.ACTION_UP,50);
                check(!view.startFling(event,1000), "tap became fling");
                view.mTouchScrolling=true; view.mSelecting=true;
                check(!view.startFling(event,1000), "selection became fling");
                view.mSelecting=false; view.mFontScaleGesture=true;
                check(!view.startFling(event,1000), "pinch became fling");
                view.mFontScaleGesture=false; view.mImageGesture=true;
                check(!view.startFling(event,1000), "image action became fling");
                view.mImageGesture=false;
                check(!view.startFling(mouse(MotionEvent.ACTION_UP,50),1000), "hardware mouse became fling");
                check(!view.startFling(event,10), "slow release became fling");
                """);
    }

    @Test public void pressingAnimatedApplicationContentSettlesWithoutClickingAnotherRow() throws Exception {
        verify("""
                for (boolean finger : new boolean[] {true,false}) {
                    View view=new View(); view.mSession.emulator.tracking=true; view.mRegionScroll.active=true;
                    MotionEvent down=finger?touch(MotionEvent.ACTION_DOWN,30):mouse(MotionEvent.ACTION_DOWN,30);
                    MotionEvent up=finger?touch(MotionEvent.ACTION_UP,30):mouse(MotionEvent.ACTION_UP,30);
                    view.onTouchEvent(down); view.onTouchEvent(up);
                    check(!view.mRegionScroll.active && view.mSession.emulator.events.isEmpty(),
                            "stopping animation clicked a different row");
                    view.onTouchEvent(down); view.onTouchEvent(up);
                    check(view.mSession.emulator.events.equals(List.of("0:true","0:false")),
                            "settled content did not accept next click");
                }
                """);
    }

    @Test public void cancelledGesturesAndWindowOrBufferChangesStopFurtherInput() throws Exception {
        verify("""
                View view=new View(); view.mTouchScrolling=true; MotionEvent event=touch(MotionEvent.ACTION_UP,50);
                view.startFling(event,1000); view.mSession.emulator.tracking=true;
                view.mScroller.y=-30; view.computeScroll();
                check(view.mFlingEvent==null && view.mSession.emulator.events.isEmpty(), "mode change received stale wheel input");
                view.startFling(event,1000); view.onTouchEvent(touch(MotionEvent.ACTION_CANCEL,50));
                check(view.mFlingEvent==null, "cancel retained fling");
                view.startFling(event,1000); view.onWindowFocusChanged(false);
                check(view.mFlingEvent==null, "background window retained fling");
                view.startFling(event,1000); view.onDetachedFromWindow();
                check(view.mFlingEvent==null, "detached window retained fling");
                view.startFling(event,1000); view.mScroller.finished=true; view.computeScroll();
                check(view.mFlingEvent==null, "completed animation retained MotionEvent");
                """);
    }

    @Test public void fingerFollowsTheContentAndStopsAtTranscriptBounds() throws Exception {
        verify("""
                View view = new View();
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN, 20));
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE, 50));
                check(view.mViewport.topRow() == -3, "dragging down must reveal older output");
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE, 40));
                check(view.mViewport.topRow() == -2, "reversing drag must move toward live output");
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE, 500));
                check(view.mViewport.topRow() == -20, "scroll exceeded transcript");
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE, 0));
                check(view.mViewport.topRow() == 0, "scroll exceeded live output");
                view.onTouchEvent(touch(MotionEvent.ACTION_UP, 0));
                check(view.keyboardRequests == 0, "scroll opened keyboard");
                """);
    }

    @Test public void touchScrollInMouseTrackingModeIsNotAMouseDrag() throws Exception {
        verify("""
                View view = new View();
                view.mSession.emulator.tracking = true;
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN, 20));
                check(view.mSession.emulator.events.isEmpty(), "touch down started mouse selection");
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE, 50));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP, 50));
                check(view.mSession.emulator.events.equals(List.of("64:true", "64:true", "64:true")),
                        "touch scroll did not send wheel-up events");
                check(view.mViewport.topRow() == 0, "application scroll changed transcript viewport");
                check(view.keyboardRequests == 0, "scroll opened keyboard");
                """);
    }

    @Test public void mouseTrackingTapAndHardwareDragKeepTheirMeaning() throws Exception {
        verify("""
                View view = new View();
                view.mSession.emulator.tracking = true;
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN, 20));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP, 20));
                check(view.mSession.emulator.events.equals(List.of("0:true", "0:false")), "touch tap lost click");
                view.mSession.emulator.events.clear();
                view.onTouchEvent(mouse(MotionEvent.ACTION_DOWN, 20));
                view.onTouchEvent(mouse(MotionEvent.ACTION_MOVE, 50));
                view.onTouchEvent(mouse(MotionEvent.ACTION_UP, 50));
                check(view.mSession.emulator.events.equals(List.of("0:true", "32:true", "0:false")),
                        "hardware mouse drag changed");
                """);
    }

    @Test public void smallFingerAndWheelDeltasAreAccumulated() throws Exception {
        verify("""
                View view = new View();
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN, 20));
                for(int y=23;y<=30;y++) view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,y));
                check(view.mViewport.topRow() == -1, "small touch deltas were lost");
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,30));
                MotionEvent wheel=mouse(MotionEvent.ACTION_SCROLL,20); wheel.wheel=0.25f;
                for(int i=0;i<4;i++) view.onGenericMotionEvent(wheel);
                check(view.mViewport.topRow() == -4, "high-resolution wheel deltas were lost or amplified");
                check(view.computeVerticalScrollRange()==300 && view.computeVerticalScrollExtent()==100
                        && view.computeVerticalScrollOffset()==160, "scrollbar does not describe pixel viewport");
                """);
    }

    @Test public void shiftWheelReadsHistoryWithoutSendingApplicationInput() throws Exception {
        verify("""
                View view = new View(); view.mSession.emulator.tracking=true;
                MotionEvent wheel=mouse(MotionEvent.ACTION_SCROLL,20); wheel.wheel=1; wheel.shift=true;
                view.onGenericMotionEvent(wheel);
                check(view.mViewport.topRow() == -3, "Shift+wheel did not read local history");
                check(view.mSession.emulator.events.isEmpty(), "Shift+wheel reached terminal application");
                """);
    }

    @Test public void alternateScreenWithoutMouseTrackingReceivesArrowKeys() throws Exception {
        verify("""
                View view = new View(); view.mSession.emulator.alternate=true;
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,40));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,40));
                check(view.mSession.output.equals("upup"), "alternate screen cannot scroll");
                check(view.mViewport.topRow()==0, "alternate screen created fake scrollback");
                """);
    }

    @Test public void selectionAndCancelledTouchesNeverBecomeTaps() throws Exception {
        verify("""
                View view = new View(); view.mSession.emulator.tracking=true;
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                view.beginSelection(touch(MotionEvent.ACTION_DOWN,20));
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,50));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,50));
                check(view.mViewport.topRow()==0 && view.mSession.emulator.events.isEmpty(), "selection became scroll or click");
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                view.onTouchEvent(touch(MotionEvent.ACTION_CANCEL,20));
                check(view.keyboardRequests==0 && view.mSession.emulator.events.isEmpty(), "cancel became tap");
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                MotionEvent horizontal=touch(MotionEvent.ACTION_MOVE,20); horizontal.x=60;
                view.onTouchEvent(horizontal);
                MotionEvent up=touch(MotionEvent.ACTION_UP,20); up.x=60;
                view.onTouchEvent(up);
                check(view.mSession.emulator.events.isEmpty(), "horizontal swipe became a tap");
                """);
    }

    @Test public void shiftPageKeysReadHistoryWhileOrdinaryKeysReachThePty() throws Exception {
        verify("""
                View view = new View(); KeyEvent event=new KeyEvent(); event.shift=true;
                view.onKeyDown(KeyEvent.KEYCODE_PAGE_UP,event);
                check(view.mViewport.topRow() == -10 && view.mSession.output.isEmpty(), "Shift+PageUp escaped to PTY");
                view.onKeyDown(KeyEvent.KEYCODE_PAGE_DOWN,event);
                check(view.mViewport.topRow() == 0 && view.mSession.output.isEmpty(), "Shift+PageDown escaped to PTY");
                event.shift=false; view.onKeyDown(KeyEvent.KEYCODE_PAGE_UP,event);
                check(view.mSession.output.equals("key"), "ordinary PageUp did not reach PTY");
                """);
    }

    @Test public void controlWheelZoomsOnlyThisWindowWithoutTerminalInput() throws Exception {
        verify("""
                View view = new View(); View other = new View(); view.mSession.emulator.tracking=true;
                MotionEvent wheel=mouse(MotionEvent.ACTION_SCROLL,20); wheel.ctrl=true; wheel.wheel=0.25f;
                for(int i=0;i<4;i++) view.onGenericMotionEvent(wheel);
                check(view.fontSizeSp()==15 && other.fontSizeSp()==14, "zoom is not local to this window");
                check(view.fontRefreshes==1, "fractional zoom reflows before a font step");
                check(view.mViewport.topRow()==0 && view.mSession.emulator.events.isEmpty()
                        && view.mSession.output.isEmpty(), "zoom reached tmux");
                wheel.wheel=100; view.onGenericMotionEvent(wheel);
                check(view.fontSizeSp()==40, "zoom exceeded maximum font size");
                wheel.wheel=-100; view.onGenericMotionEvent(wheel);
                check(view.fontSizeSp()==8, "zoom exceeded minimum font size");
                """);
    }

    @Test public void pinchConsumesTheWholeGestureIncludingTheRemainingFinger() throws Exception {
        verify("""
                View view = new View(); view.mSession.emulator.tracking=true;
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                MotionEvent two=touch(MotionEvent.ACTION_POINTER_DOWN,20); two.pointers=2;
                view.onTouchEvent(two);
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,60));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,60));
                check(view.mGestures.cancelled==1, "pinch retained long-press recognition");
                check(view.mViewport.topRow()==0 && !view.mSelecting && view.mSession.emulator.events.isEmpty()
                        && view.keyboardRequests==0, "pinch became scroll, click, or selection");
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,20));
                check(view.mSession.emulator.events.equals(List.of("0:true", "0:false")), "pinch blocked the next tap");
                """);
    }

    @Test public void pinchAccumulatesSmallScaleFactorsBeforeReflow() throws Exception {
        verify("""
                View view = new View(); ScaleGestureDetector scale=new ScaleGestureDetector(); scale.factor=1.01f;
                view.onScaleBegin(scale);
                for(int i=0;i<4;i++) view.onScale(scale);
                check(view.fontSizeSp()==15 && view.fontRefreshes==1, "small scale factors were lost or reflowed repeatedly");
                scale.factor=100; view.onScale(scale); check(view.fontSizeSp()==40, "pinch exceeds limit");
                scale.factor=0.001f; view.onScale(scale); check(view.fontSizeSp()==8, "pinch exceeds lower limit");
                """);
    }

    @Test public void linksRequireATapAndDoNotStealTerminalMouseReporting() throws Exception {
        verify("""
                View view=new View(); view.link=new TerminalHyperlink("https://example.com");
                List<String> opened=new ArrayList<>();
                view.mActions=new ClipboardActions() {
                    public void copySelection() {} public void pasteClipboard() {}
                    public void showLink(TerminalHyperlink link) { opened.add(link.uri()); }
                };
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN, 10));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP, 10));
                check(opened.size()==1 && view.keyboardRequests==0, "link tap did not use explicit actions");
                view.mSession.emulator.tracking=true;
                view.onTouchEvent(mouse(MotionEvent.ACTION_DOWN, 10));
                view.onTouchEvent(mouse(MotionEvent.ACTION_UP, 10));
                check(opened.size()==1 && view.mSession.emulator.events.size()==2, "link stole TUI input");
                MotionEvent down=mouse(MotionEvent.ACTION_DOWN,10); down.ctrl=true;
                MotionEvent up=mouse(MotionEvent.ACTION_UP,10); up.ctrl=true;
                view.onTouchEvent(down); view.onTouchEvent(up);
                check(opened.size()==2 && view.mSession.emulator.events.size()==2, "Ctrl link sent TUI input");
                view.mSession.emulator.tracking=false;
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,10));
                view.onTouchEvent(touch(MotionEvent.ACTION_CANCEL,10));
                check(opened.size()==2, "cancel activated link");
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,10));
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,50));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,50));
                check(opened.size()==2, "scroll activated link");
                """);
    }

    @Test public void imageActionsPreserveScrollingSelectionAndApplicationMouseInput() throws Exception {
        verify("""
                View view=new View(); view.image=new TerminalImage();
                int[] opened={0};
                view.mActions=new ClipboardActions() {
                    public void copySelection() {} public void pasteClipboard() {}
                    public void showLink(TerminalHyperlink link) {}
                    public void showImage(TerminalImage image) { opened[0]++; }
                };
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,10));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,10));
                check(opened[0]==1 && view.keyboardRequests==0, "image tap opened IME");
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,10));
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,50));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,50));
                check(opened[0]==1, "image intercepted scrolling");
                view.mSession.emulator.tracking=true;
                view.onTouchEvent(mouse(MotionEvent.ACTION_DOWN,10));
                view.onTouchEvent(mouse(MotionEvent.ACTION_UP,10));
                check(opened[0]==1 && view.mSession.emulator.events.size()==2, "image stole TUI click");
                MotionEvent down=mouse(MotionEvent.ACTION_DOWN,10); down.ctrl=true;
                MotionEvent up=mouse(MotionEvent.ACTION_UP,10); up.ctrl=true;
                view.onTouchEvent(down); view.onTouchEvent(up);
                check(opened[0]==2 && view.mSession.emulator.events.size()==2, "Ctrl image sent TUI input");
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,10));
                view.showImageAt(touch(MotionEvent.ACTION_DOWN,10));
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,10));
                check(opened[0]==3 && view.mSession.emulator.events.size()==2 && !view.mSelecting,
                        "long-press release became a second action");
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,10));
                view.onTouchEvent(touch(MotionEvent.ACTION_CANCEL,10));
                check(opened[0]==3, "cancel exported image");
                down=mouse(MotionEvent.ACTION_DOWN,10); down.shift=true;
                up=mouse(MotionEvent.ACTION_UP,10); up.shift=true;
                view.onTouchEvent(down); view.onTouchEvent(up);
                check(opened[0]==3, "Shift selection opened image");
                """);
    }

    @Test public void localHistoryMovesImmediatelyWithinARowAndAcrossSeparateGestures() throws Exception {
        verify("""
                View view=new View();
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,20));
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,23));
                check(view.mViewport.topRow()==-1 && view.mViewport.rowOffset()==7, "sub-row drag was rounded away");
                view.onTouchEvent(touch(MotionEvent.ACTION_UP,23));
                view.onTouchEvent(touch(MotionEvent.ACTION_DOWN,40));
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,44));
                check(view.mViewport.topRow()==-1 && view.mViewport.rowOffset()==3, "new gesture discarded fractional position");
                view.onTouchEvent(touch(MotionEvent.ACTION_MOVE,43));
                check(view.mViewport.topRow()==-1 && view.mViewport.rowOffset()==4, "direction reversal waited for another row");
                check(view.mSession.output.isEmpty() && view.mSession.emulator.events.isEmpty(), "history sent input");
                """);
    }

    @Test public void flingFinishesThePartialOldestRowBeforeStopping() throws Exception {
        verify("""
                View view=new View(); view.mTouchScrolling=true;
                view.startFling(touch(MotionEvent.ACTION_UP,50),1000);
                view.mScroller.y=-195; view.computeScroll();
                check(view.mViewport.topRow()==-20 && view.mViewport.rowOffset()==5 && view.mFlingEvent!=null,
                        "fling stopped before reaching the oldest row's top");
                view.mScroller.y=-200; view.computeScroll();
                check(view.mViewport.topRow()==-20 && view.mViewport.rowOffset()==0 && view.mFlingEvent==null, "fling missed exact edge");
                view.scrollHistoryPixels(-1000); view.scrollHistoryPixels(1);
                check(view.mViewport.topRow()==-20 && view.mViewport.rowOffset()==1, "overscroll created a reverse-direction dead zone");
                view.scrollToBottom();
                check(view.mViewport.topRow()==0 && view.mViewport.rowOffset()==0, "live output retained fractional scroll");
                """);
    }

    @Test public void localWheelPreservesFractionalPixelsButApplicationWheelKeepsWholeEvents() throws Exception {
        verify("""
                View view=new View(); MotionEvent wheel=mouse(MotionEvent.ACTION_SCROLL,20); wheel.wheel=0.25f;
                view.onGenericMotionEvent(wheel);
                check(view.mViewport.topRow()==-1 && view.mViewport.rowOffset()==2.5f, "fractional wheel was rounded to a line");
                wheel.wheel=-0.1f; view.onGenericMotionEvent(wheel);
                check(view.mViewport.topRow()==-1 && view.mViewport.rowOffset()==5.5f, "fractional wheel reversal was lost");
                view.scrollToBottom(); view.mSession.emulator.tracking=true; wheel.wheel=0.25f;
                view.onGenericMotionEvent(wheel);
                check(view.mSession.emulator.events.isEmpty(), "TUI got a fractional wheel event");
                view.onGenericMotionEvent(wheel);
                check(view.mSession.emulator.events.equals(List.of("64:true")) && view.mViewport.rowOffset()==0,
                        "application wheel changed local geometry");
                """);
    }

    @Test public void outputKeepsTheScrolledAnchorAndBufferChangesResetOffset() throws Exception {
        verify("""
                View view=new View(); view.scrollHistoryPixels(-13);
                view.mSession.emulator.scrolled=3; view.onTerminalChanged();
                check(view.mViewport.topRow()==-5 && view.mViewport.rowOffset()==7, "output moved the history anchor");
                check(view.mSession.emulator.scrolled==0, "scroll counter not consumed");
                view.mSession.emulator.scrolled=30; view.onTerminalChanged();
                check(view.mViewport.topRow()==-20 && view.mViewport.rowOffset()==0, "evicted history retained a fractional oldest row");
                view.scrollHistoryPixels(3); view.mSession.emulator.alternate=true; view.onTerminalChanged();
                check(view.mViewport.topRow()==0 && view.mViewport.rowOffset()==0, "alternate screen inherited local scroll offset");
                view.mSession.emulator.alternate=false; view.mSession.emulator.scrolled=1; view.onTerminalChanged();
                check(view.mViewport.topRow()==0 && view.mViewport.rowOffset()==0, "live output stopped following new lines");
                """);
    }

    @Test public void transcriptCoordinatesIncludeBothPartialRowsWithoutChangingMouseCoordinates() throws Exception {
        verify("""
                View view=new View(); view.scrollHistoryPixels(-13);
                check(view.visibleRowCount()==11, "partial bottom row is omitted");
                check(view.transcriptCellAt(touch(MotionEvent.ACTION_DOWN,2)).y==-2, "top fragment maps to wrong line");
                check(view.transcriptCellAt(touch(MotionEvent.ACTION_DOWN,3)).y==-1, "line boundary ignores offset");
                check(view.transcriptCellAt(touch(MotionEvent.ACTION_DOWN,99)).y==8, "bottom fragment is inaccessible");
                check(view.cellAt(touch(MotionEvent.ACTION_DOWN,3)).y==0, "terminal mouse protocol inherited scroll offset");
                check(view.rowBottomY(-2)==3 && view.rowBottomY(8)==103, "anchors use a different transform");
                """);
    }

    @Test public void evictingSelectionAlsoReleasesItsGesture() throws Exception {
        verify("""
                View view=new View(); view.mViewport.select(1,-19,5,-18); view.mSelecting=true;
                view.mSession.emulator.scrolled=1; view.onTerminalChanged();
                check(view.hasSelection() && view.mSelecting, "live selection was cleared early");
                view.mSession.emulator.scrolled=1; view.onTerminalChanged();
                check(!view.hasSelection() && !view.mSelecting, "evicted selection retained its gesture");
                """);
    }

    private static void verify(final String body) throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class android { static class os { static class SystemClock {
                    static long uptimeMillis() { return 0; }
                } } }
                static class Point { int x,y; Point(int x,int y) { this.x=x; this.y=y; } }
                static class MotionEvent {
                    static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3,
                            ACTION_POINTER_DOWN=5, ACTION_SCROLL=8;
                    static final int AXIS_VSCROLL=9;
                    int action, pointers=1; final float y; boolean touch=true, shift, ctrl; float wheel, x=10;
                    MotionEvent(int action,float y) { this.action=action; this.y=y; }
                    int getActionMasked() { return action; } float getX() { return x; }
                    float getY() { return y; } float getAxisValue(int axis) { return wheel; }
                    int getMetaState() { return ctrl ? KeyEvent.META_CTRL_ON : 0; }
                    int getPointerCount() { return pointers; }
                    boolean recycled;
                    void setAction(int value) { action=value; } void recycle() { recycled=true; }
                    static MotionEvent obtain(MotionEvent e) {
                        MotionEvent copy=new MotionEvent(e.action,e.y); copy.x=e.x;
                        copy.touch=e.touch; copy.shift=e.shift; copy.ctrl=e.ctrl; return copy;
                    }
                }
                static MotionEvent touch(int action,float y) { return new MotionEvent(action,y); }
                static MotionEvent mouse(int action,float y) { var e=touch(action,y); e.touch=false; return e; }
                static class TerminalEmulator {
                    void removeScrollListener(Object listener) {}
                    static final int MOUSE_LEFT_BUTTON=0, MOUSE_LEFT_BUTTON_MOVED=32,
                            MOUSE_WHEELUP_BUTTON=64, MOUSE_WHEELDOWN_BUTTON=65;
                    boolean tracking, alternate; final List<String> events=new ArrayList<>();
                    boolean isMouseTrackingActive() { return tracking; }
                    boolean isAlternateBufferActive() { return alternate; }
                    boolean isCursorKeysApplicationMode() { return false; }
                    boolean isKeypadApplicationMode() { return false; }
                    TerminalEmulator getScreen() { return this; }
                    int scrolled;
                    int getScrollCounter() { return scrolled; }
                    void clearScrollCounter() { scrolled=0; }
                    int getActiveTranscriptRows() { return alternate ? 0 : 20; }
                    void sendMouseEvent(int button,int x,int y,boolean pressed) { events.add(button+":"+pressed); }
                }
                static class KeyEvent {
                    static final int KEYCODE_DPAD_UP=19, KEYCODE_DPAD_DOWN=20, KEYCODE_BACK=4,
                            KEYCODE_PAGE_UP=92, KEYCODE_PAGE_DOWN=93, KEYCODE_C=31, KEYCODE_V=50, META_CTRL_ON=4096;
                    boolean shift, alt, ctrl;
                    boolean isShiftPressed() { return shift; } boolean isAltPressed() { return alt; }
                    boolean isCtrlPressed() { return ctrl; }
                }
                static class KeyHandler {
                    static String getCode(int key,int mods,boolean cursor,boolean keypad) { return key==19?"up":"down"; }
                }
                static class Session {
                    final TerminalEmulator emulator=new TerminalEmulator(); String output="";
                    TerminalEmulator emulator() { return emulator; } void write(String s) { output+=s; }
                }
                static class Renderer { float cellHeight() { return 10; } }
                static class OverScroller {
                    int y,velocity; boolean finished=true;
                    void forceFinished(boolean value) { finished=value; }
                    void fling(int x,int y,int vx,int vy,int minX,int maxX,int minY,int maxY) {
                        this.y=y;velocity=vy;finished=false;
                    }
                    boolean computeScrollOffset() { return !finished; }
                    int getCurrY() { return y; }
                }
                static class ViewConfiguration {
                    static ViewConfiguration get(Object context) { return new ViewConfiguration(); }
                    int getScaledMinimumFlingVelocity() { return 50; }
                }
                static class Gestures {
                    int cancelled;
                    void onTouchEvent(MotionEvent e) { if(e.action==MotionEvent.ACTION_CANCEL) cancelled++; }
                }
                static class ScaleGestureDetector {
                    float factor=1; float getScaleFactor() { return factor; }
                    void onTouchEvent(MotionEvent e) {}
                }
                static class ConsolePreferences {
                    static final int MIN_FONT_SIZE_SP=8, MAX_FONT_SIZE_SP=40;
                """ + RuntimeSourceFixture.methods("ConsolePreferences", "clampFontSize") + """
                }
                static class BaseView {
                    boolean onGenericMotionEvent(MotionEvent e) { return false; }
                    boolean onKeyDown(int key,KeyEvent event) { return false; }
                    void onDetachedFromWindow() {} void onWindowFocusChanged(boolean focused) {}
                }
                static class Input { String key(KeyEvent e,TerminalEmulator t) { return "key"; } }
                record TerminalHyperlink(String uri) {}
                static class TerminalImage {}
                interface ClipboardActions { void copySelection(); void pasteClipboard(); void showLink(TerminalHyperlink link);
                    default void showImage(TerminalImage image) {} }
                static class View extends BaseView {
                    final Resettable mRegionScroll=new Resettable(); Object mScrollListener;
                    static class Resettable {
                        int resets,inputs,ends; boolean active,continuous;
                        void reset() { resets++; active=false; }
                        boolean advance(long now) { return active; }
                        void input(int rows,boolean continuous,double frame,long now) {
                            inputs++; this.continuous=continuous;
                        }
                        void endInput(long now) { ends++; }
                    }
                    Object getDisplay() { return this; }
                    double scrollFrameMillis() { return 16; }
                    Session mSession=new Session(); Renderer mRenderer=new Renderer(); Gestures mGestures=new Gestures();
                    OverScroller mScroller=new OverScroller(); MotionEvent mFlingEvent;
                    int mLastFlingY,animationFrames; boolean mFlingMouseTracking,mFlingAlternateBuffer;
                    ScaleGestureDetector mScaleGestures=new ScaleGestureDetector();
                    boolean mFontScaleGesture; int mFontSizeSp=14, fontRefreshes; float mPinchFontSizeSp, mFontWheelRemainder;
                    Input mInput=new Input(); ClipboardActions mActions;
                    TerminalHyperlink link; TerminalHyperlink linkAt(MotionEvent event) { return link; }
                    TerminalImage image; TerminalImage imageAt(MotionEvent event) { return image; }
                    boolean mImageGesture;
                    static final int SCROLL_ROWS=3;
                    int mContentPadding, mTouchSlop=2, mTerminalMouseButton, keyboardRequests;
                    final TerminalViewport mViewport=new TerminalViewport();
                    { mViewport.metrics(10,10,0); mViewport.resize(200,100); }
                    boolean hasSelection() { return mViewport.hasSelection(); }
                    float mDownX, mDownY, mLastTouchY, mTouchScrollRemainder, mWheelScrollRemainder;
                    boolean mTouchScrolling, mSelecting, mTerminalMousePress;
                    void requestFocus() {} void invalidate() {} void awakenScrollBars() {}
                    void updateSelectionHandles() {}
                    Handles mSelectionHandles; static class Handles { void hide() {} }
                    Object getContext() { return this; }
                    void postInvalidateOnAnimation() { animationFrames++; }
                    void refreshFontMetrics() { fontRefreshes++; }
                    void clearSelection() { mSelecting=false; mViewport.clearSelection(); }
                    void beginSelection(MotionEvent e) { mSelecting=true; }
                    void updateSelection(Point cell) {} void showSoftKeyboard() { keyboardRequests++; }
                    static boolean isTouch(MotionEvent e) { return e.touch; }
                    static boolean isShiftPressed(MotionEvent e) { return e.shift; }
                    static int mouseButton(MotionEvent e) { return 0; }
                    Point cellAt(MotionEvent e) { return new Point(1,Math.max(0,Math.min(mViewport.rows()-1,(int)e.y/10))); }
                """ + RuntimeSourceFixture.methods("ConsoleTerminalView", "onTouchEvent", "onGenericMotionEvent",
                        "scrollRows", "clampTopRow", "scrollTerminal", "scrollTouch", "scrollPixels", "onKeyDown",
                        "scrollHistoryPixels", "scrollsLocalHistory", "scrollToBottom", "onTerminalChanged",
                        "viewportRowAt", "rowBottomY", "visibleRowCount", "transcriptCellAt",
                        "startFling", "stopFling", "computeScroll", "onDetachedFromWindow", "onWindowFocusChanged",
                        "handleFontScaleGesture", "fontSizeSp", "setFontSizeSp", "onScaleBegin", "onScale", "showImageAt",
                        "computeVerticalScrollRange", "computeVerticalScrollExtent", "computeVerticalScrollOffset")
                + "}\npublic static void verify() {\n" + body + "\n}", "TerminalViewport");
    }
}
