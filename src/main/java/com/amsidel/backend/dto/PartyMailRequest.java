package com.amsidel.backend.dto;

import com.amsidel.backend.entity.TradeParty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** One personal mail to one customer or supplier (Product portal mail); the content is in MailContent. */
@Getter
@Setter
public class PartyMailRequest extends MailContent {

    @NotNull
    private TradeParty party;

    @NotNull
    private Long partyId;
}
