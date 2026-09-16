package fi.bundo.household

import fi.bundo.data.WelcomeProgress
import fi.bundo.data.WelcomeStep

/** An old candidate record for a family does not mean a new invitation was redeemed. */
internal fun recoverWelcomeHome(progress: WelcomeProgress, homes: List<Household>): Household? = when (progress.step) {
    WelcomeStep.HOME -> homes.find { it.id == progress.homeId }
    WelcomeStep.CREATE -> homes.find { it.id == progress.createId }
    WelcomeStep.JOIN -> InvitationLink.parse(progress.link)?.let { link ->
        homes.find { home ->
            home.id == link.workspace && home.epoch == link.epoch &&
                (home.active && !home.deleted || home.invitations.any { it.id == link.invitation })
        }
    }
    else -> null
}
