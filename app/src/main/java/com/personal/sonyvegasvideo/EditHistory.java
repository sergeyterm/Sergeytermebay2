package com.personal.sonyvegasvideo;

import java.util.ArrayDeque;

/** One undo entry per gesture; playback position is deliberately not an edit. */
public final class EditHistory {
    public static final class State {
        public final int start, end;
        public final boolean muted;
        public final EffectSpec effects;
        public final java.util.List<Integer> markers;
        public State(int start, int end, boolean muted) {this(start,end,muted,new EffectSpec(),java.util.Collections.emptyList());}
        public State(int start,int end,boolean muted,EffectSpec effects,java.util.List<Integer> markers) {
            this.start=start;this.end=end;this.muted=muted;this.effects=effects.copy().normalize();
            this.markers=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(markers));
        }
        public boolean same(State other) {return start==other.start && end==other.end && muted==other.muted&&effects.equals(other.effects)&&markers.equals(other.markers);}
    }
    private final ArrayDeque<State> undo=new ArrayDeque<>(), redo=new ArrayDeque<>();
    public void record(State before,State after){if(before.same(after))return;undo.addLast(before);if(undo.size()>64)undo.removeFirst();redo.clear();}
    public boolean canUndo(){return !undo.isEmpty();}
    public boolean canRedo(){return !redo.isEmpty();}
    public State undo(State current){if(undo.isEmpty())return current;redo.addLast(current);return undo.removeLast();}
    public State redo(State current){if(redo.isEmpty())return current;undo.addLast(current);return redo.removeLast();}
    public void clear(){undo.clear();redo.clear();}
    public java.util.List<State> undoStates(){return new java.util.ArrayList<>(undo);}
    public java.util.List<State> redoStates(){return new java.util.ArrayList<>(redo);}
    public void restore(java.util.List<State> previous,java.util.List<State> following){clear();for(State state:previous){undo.addLast(state);if(undo.size()>64)undo.removeFirst();}for(State state:following){redo.addLast(state);if(redo.size()>64)redo.removeFirst();}}
}
