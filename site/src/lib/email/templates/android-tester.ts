import { appUrl as resolveAppUrl } from '@/lib/env'
import {
  ANDROID_BETA_PATH,
  ANDROID_PLAY_STORE_URL,
  ANDROID_TESTING_OPT_IN_URL,
  TEST_DAYS,
} from '@/lib/android-testers'

// Emails for the Android closed-test flow (see lib/android-testers.ts):
//   androidTesterSignupEmail — sent when someone signs up at /android-beta
//   androidTesterInviteEmail — sent by an admin once the address is on the
//                              Play Console tester list; carries the opt-in link
//
// Neither email contains anything the visitor typed. The signup form is
// public and the address is not verified, so any echoed text (a "name") would
// let a stranger have GCC deliver their words to someone else's inbox.

interface Step {
  title: string
  body: string
  link?: { href: string; label: string }
}

function layout(opts: { heading: string; intro: string; steps: Step[]; outro: string; footer: string }) {
  const stepsHtml = opts.steps
    .map(
      (step, i) => `
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 16px;">
                <tr>
                  <td width="44" valign="top">
                    <div style="width: 32px; height: 32px; border-radius: 16px; background-color: #16763a; color: #ffffff; font-size: 15px; font-weight: 700; line-height: 32px; text-align: center;">${i + 1}</div>
                  </td>
                  <td valign="top">
                    <p style="margin: 4px 0 4px; color: #1a1a1a; font-size: 16px; font-weight: 600;">${step.title}</p>
                    <p style="margin: 0; color: #4a4a4a; font-size: 15px; line-height: 1.6;">${step.body}</p>
                    ${
                      step.link
                        ? `<p style="margin: 12px 0 0;"><a href="${step.link.href}" style="display: inline-block; background-color: #16763a; color: #ffffff; font-size: 15px; font-weight: 600; text-decoration: none; padding: 12px 20px; border-radius: 8px;">${step.link.label}</a></p>`
                        : ''
                    }
                  </td>
                </tr>
              </table>`,
    )
    .join('')

  const html = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${opts.heading}</title>
</head>
<body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f5f5f5;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color: #f5f5f5; padding: 40px 20px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width: 600px; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);">
          <tr>
            <td style="background: linear-gradient(135deg, #0f5228 0%, #0b3b1d 100%); padding: 32px; text-align: center;">
              <h1 style="margin: 0; color: #ffffff; font-size: 26px; font-weight: 700;">Global Community Cleaners</h1>
              <p style="margin: 8px 0 0; color: #cbe0d3; font-size: 14px; font-weight: 500;">Android test group</p>
            </td>
          </tr>
          <tr>
            <td style="padding: 40px 32px;">
              <h2 style="margin: 0 0 16px; color: #1a1a1a; font-size: 22px;">${opts.heading}</h2>
              <p style="margin: 0 0 28px; color: #4a4a4a; font-size: 16px; line-height: 1.6;">${opts.intro}</p>
              ${stepsHtml}
              <p style="margin: 28px 0 0; color: #4a4a4a; font-size: 15px; line-height: 1.6;">${opts.outro}</p>
            </td>
          </tr>
          <tr>
            <td style="background-color: #f9f9f9; padding: 24px 32px; text-align: center; border-top: 1px solid #e5e5e5;">
              <p style="margin: 0 0 8px; color: #6b6b6b; font-size: 12px;">Global Community Cleaners | Chicago, IL</p>
              <p style="margin: 0; color: #9a9a9a; font-size: 12px;">${opts.footer}</p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>
`

  // Plain-text part: drop tags, then turn the entities used above back into characters.
  const strip = (s: string) =>
    s
      .replace(/<[^>]+>/g, '')
      .replace(/&ldquo;|&rdquo;|&quot;/g, '"')
      .replace(/&rsquo;/g, "'")
      .replace(/&mdash;/g, '-')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&amp;/g, '&')
  const text = `${opts.heading}

${strip(opts.intro)}

${opts.steps
  .map((s, i) => `${i + 1}. ${s.title}\n   ${strip(s.body)}${s.link ? `\n   ${s.link.href}` : ''}`)
  .join('\n\n')}

${strip(opts.outro)}

---
Global Community Cleaners
${strip(opts.footer)}
`

  return { html, text }
}

const STAY_STEP: Step = {
  title: `Keep it installed for ${TEST_DAYS} days`,
  body: `Google counts testers who stay in the test for ${TEST_DAYS} days in a row. Open the app, look around, and tell us what breaks. Please don&rsquo;t leave the test or uninstall before then.`,
}

const BECOME_TESTER_STEP: Step = {
  title: 'Tap &ldquo;Become a tester&rdquo;',
  body: 'Open this link on your Android phone, signed in to the same Google account, and tap <strong>Become a tester</strong>.',
  link: { href: ANDROID_TESTING_OPT_IN_URL, label: 'Become a tester' },
}

const INSTALL_STEP: Step = {
  title: 'Install the app from Google Play',
  body: `After you join, the app appears on Google Play for your account: <a href="${ANDROID_PLAY_STORE_URL}" style="color: #16763a;">open the GCC listing</a> and tap Install.`,
}

const REMOVAL_NOTE =
  'You received this email because you signed up to test the GCC Android app. Reply to this email if you want to be taken off the list.'

/** Sent on signup. With a self-join group the tester can finish right away. */
export function androidTesterSignupEmail(data: { groupUrl: string | null }) {
  const greeting = 'Thank you.'

  if (data.groupUrl) {
    return layout({
      heading: 'You can join the GCC Android test now',
      intro: `${greeting} Three short steps and you are testing the GCC app before anyone else. Do them in this order &mdash; the second link only works after the first.`,
      steps: [
        {
          title: 'Join the tester group',
          body: 'Use the Google account that is on your Android phone. Joining the group is what puts you on the tester list.',
          link: { href: data.groupUrl, label: 'Join the tester group' },
        },
        BECOME_TESTER_STEP,
        INSTALL_STEP,
        STAY_STEP,
      ],
      outro:
        'If the second link says the app is not available, give it a few minutes after joining the group and try again.',
      footer: REMOVAL_NOTE,
    })
  }

  return layout({
    heading: 'You are signed up to test GCC on Android',
    intro: `${greeting} You are helping us put the GCC app on Google Play for everyone. Google asks every new app to be tried by a small group first, and you are now part of ours.`,
    steps: [
      {
        title: 'We add you to the tester list',
        body: 'We add your Google account to our tester list by hand, usually within a day or two.',
      },
      {
        title: 'We email you the join link',
        body: 'The next email from us has one link. Open it on your Android phone and tap <strong>Become a tester</strong>, then install the app from Google Play.',
      },
      STAY_STEP,
    ],
    outro: `Nothing to do until that email arrives. You can read more about the test at <a href="${resolveAppUrl()}${ANDROID_BETA_PATH}" style="color: #16763a;">${resolveAppUrl().replace(/^https?:\/\//, '')}${ANDROID_BETA_PATH}</a>.`,
    footer: REMOVAL_NOTE,
  })
}

/** Sent by an admin once the address is on the Play Console tester list. */
export function androidTesterInviteEmail() {
  return layout({
    heading: 'Your GCC Android test invite is ready',
    intro: 'Your Google account is now on our tester list. Two taps and the app is on your phone.',
    steps: [BECOME_TESTER_STEP, INSTALL_STEP, STAY_STEP],
    outro:
      'If the link says the app is not available, check that your phone is signed in to Google Play with the email address this message was sent to, then try again. Still stuck? Reply to this email and we will sort it out.',
    footer: REMOVAL_NOTE,
  })
}
