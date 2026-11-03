package com.personal.sonyvegasvideo;

import java.util.ArrayDeque;

/** One undo entry per gesture; playback position is deliberately not an edit. */
public final class EditHistory {
    public static final class State {
        public final int start, end;
        public final boolean muted;
        public State(int start, int end, boolean muted) {this.start=start;this.end=end;this.muted=muted;}
        public boolean same(State other) {return start==other.start && end==other.end && muted==other.muted;}
    }
    private final ArrayDeque<State> undo=new ArrayDeque<>(), redo=new ArrayDeque<>();
    public void record(State before,State after){if(before.same(after))return;undo.addLast(before);if(undo.size()>64)undo.removeFirst();redo.clear();}
    public boolean canUndo(){return !undo.isEmpty();}
    public boolean canRedo(){return !redo.isEmpty();}
    public State undo(State current){if(undo.isEmpty())return current;redo.addLast(current);return undo.removeLast();}
    public State redo(State current){if(redo.isEmpty())return current;undo.addLast(current);return redo.removeLast();}
    public void clear(){undo.clear();redo.clear();}
}
