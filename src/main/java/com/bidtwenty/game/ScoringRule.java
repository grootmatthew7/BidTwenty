package com.bidtwenty.game;

import com.bidtwenty.model.Participant;

import java.util.List;

/**
 * Strategy for computing a full result from a rostered game state. The default
 * implementation is a straight-sum, but future sports can plug in their own
 * scoring formulas here.
 */
public interface ScoringRule {
    ScoringEngine.Result evaluate(List<Participant> participants);
}
