import React from 'react'
import { Composition, Sequence, Audio } from 'remotion'
import { staticFile } from 'remotion'
import { formats, sceneTiming, secondsToFrames, TOTAL_FRAMES, FPS } from './lib/timing'

// Scene imports - PRE-LAUNCH VERSION (removed past metrics scenes)
import { Scene01_VideoHook } from './components/scenes/Scene01_VideoHook'
import { Scene04_Question } from './components/scenes/Scene04_Question'
import { Scene05_AppDemo } from './components/scenes/Scene05_AppDemo'
import { Scene06_WorkerJourney } from './components/scenes/Scene06_WorkerJourney'
import { Scene07_Payment } from './components/scenes/Scene07_Payment'
// REMOVED: Scene08_Impact - showed past metrics
import { Scene09_SponsorSide } from './components/scenes/Scene09_SponsorSide'
// REMOVED: Scene10_Testimonial - showed "GCC Worker since 2024"
// REMOVED: Scene11_Stats - showed "Our Impact So Far"
import { Scene12_CTA } from './components/scenes/Scene12_CTA'
import { Scene13_LogoTagline } from './components/scenes/Scene13_LogoTagline'

// Main GCC Ad Component - PRE-LAUNCH VERSION
interface GCCAdProps {
  format: 'portrait' | 'landscape' | 'square'
}

const GCCAd: React.FC<GCCAdProps> = ({ format }) => {
  return (
    <>
      {/* Optional: Background music - uncomment when audio is added */}
      {/* <Audio src={staticFile('audio/beat.mp3')} volume={0.75} /> */}

      {/* Scene 1: Hook - Video-style storytelling with phone reveal (0-3s) */}
      <Sequence from={secondsToFrames(sceneTiming.hook.start)} durationInFrames={sceneTiming.hook.frames}>
        <Scene01_VideoHook format={format} />
      </Sequence>

      {/* Scene 2-3: App Demo - Sign up flow (2-7s) */}
      <Sequence from={secondsToFrames(sceneTiming.problem.start)} durationInFrames={sceneTiming.problem.frames + sceneTiming.statistics.frames}>
        <Scene05_AppDemo format={format} />
      </Sequence>

      {/* Scene 4: Question - What if cleaning paid? (7-10s) */}
      <Sequence from={secondsToFrames(sceneTiming.question.start)} durationInFrames={sceneTiming.question.frames}>
        <Scene04_Question format={format} />
      </Sequence>

      {/* Scene 5-6: Worker Journey - Claim & Clean (10-19s) */}
      <Sequence from={secondsToFrames(sceneTiming.workerIntro.start)} durationInFrames={sceneTiming.workerIntro.frames + sceneTiming.theClean.frames}>
        <Scene06_WorkerJourney format={format} />
      </Sequence>

      {/* Scene 7: Payment - Get paid instantly (19-22s) */}
      <Sequence from={secondsToFrames(sceneTiming.payment.start)} durationInFrames={sceneTiming.payment.frames}>
        <Scene07_Payment format={format} />
      </Sequence>

      {/* Scene 9: Sponsor Side - Your $100 employs a neighbor (22-26s) */}
      <Sequence from={secondsToFrames(sceneTiming.sponsorSide.start)} durationInFrames={sceneTiming.sponsorSide.frames}>
        <Scene09_SponsorSide format={format} />
      </Sequence>

      {/* Scene 12: CTA - Start Earning / Fund a Street (26-31s) */}
      <Sequence from={secondsToFrames(sceneTiming.ctaWorkers.start)} durationInFrames={sceneTiming.ctaWorkers.frames}>
        <Scene12_CTA format={format} />
      </Sequence>

      {/* Scene 13: Logo & Tagline (31-35s) */}
      <Sequence from={secondsToFrames(sceneTiming.logoTagline.start)} durationInFrames={sceneTiming.logoTagline.frames}>
        <Scene13_LogoTagline format={format} />
      </Sequence>
    </>
  )
}

// Wrapper components for each format to satisfy Remotion types
const GCCAdPortrait: React.FC = () => <GCCAd format="portrait" />
const GCCAdLandscape: React.FC = () => <GCCAd format="landscape" />
const GCCAdSquare: React.FC = () => <GCCAd format="square" />

// Individual scene preview components for development
const SceneHookPreview: React.FC = () => <Scene01_VideoHook format="portrait" />
const SceneQuestionPreview: React.FC = () => <Scene04_Question format="portrait" />
const SceneAppDemoPreview: React.FC = () => <Scene05_AppDemo format="portrait" />
const SceneWorkerJourneyPreview: React.FC = () => <Scene06_WorkerJourney format="portrait" />
const ScenePaymentPreview: React.FC = () => <Scene07_Payment format="portrait" />
const SceneSponsorPreview: React.FC = () => <Scene09_SponsorSide format="portrait" />
const SceneCTAPreview: React.FC = () => <Scene12_CTA format="portrait" />
const SceneLogoPreview: React.FC = () => <Scene13_LogoTagline format="portrait" />

// Landscape previews
const SceneAppDemoLandscape: React.FC = () => <Scene05_AppDemo format="landscape" />
const SceneWorkerJourneyLandscape: React.FC = () => <Scene06_WorkerJourney format="landscape" />
const ScenePaymentLandscape: React.FC = () => <Scene07_Payment format="landscape" />
const SceneSponsorLandscape: React.FC = () => <Scene09_SponsorSide format="landscape" />

// Root component with all compositions
export const RemotionRoot: React.FC = () => {
  return (
    <>
      {/* ========== FULL AD COMPOSITIONS (35s) ========== */}

      {/* Portrait (9:16) - TikTok, Instagram Reels, YouTube Shorts */}
      <Composition
        id="GCCAd-Portrait"
        component={GCCAdPortrait}
        durationInFrames={formats.portrait.durationInFrames}
        fps={formats.portrait.fps}
        width={formats.portrait.width}
        height={formats.portrait.height}
      />

      {/* Landscape (16:9) - YouTube, LinkedIn, Website */}
      <Composition
        id="GCCAd-Landscape"
        component={GCCAdLandscape}
        durationInFrames={formats.landscape.durationInFrames}
        fps={formats.landscape.fps}
        width={formats.landscape.width}
        height={formats.landscape.height}
      />

      {/* Square (1:1) - Instagram Feed, Facebook */}
      <Composition
        id="GCCAd-Square"
        component={GCCAdSquare}
        durationInFrames={formats.square.durationInFrames}
        fps={formats.square.fps}
        width={formats.square.width}
        height={formats.square.height}
      />

      {/* ========== SCENE PREVIEWS (Portrait) ========== */}

      <Composition
        id="Scene01-Hook"
        component={SceneHookPreview}
        durationInFrames={sceneTiming.hook.frames}
        fps={FPS}
        width={1080}
        height={1920}
      />

      <Composition
        id="Scene04-Question"
        component={SceneQuestionPreview}
        durationInFrames={sceneTiming.question.frames}
        fps={FPS}
        width={1080}
        height={1920}
      />

      <Composition
        id="Scene05-AppDemo"
        component={SceneAppDemoPreview}
        durationInFrames={sceneTiming.problem.frames + sceneTiming.statistics.frames}
        fps={FPS}
        width={1080}
        height={1920}
      />

      <Composition
        id="Scene06-WorkerJourney"
        component={SceneWorkerJourneyPreview}
        durationInFrames={sceneTiming.workerIntro.frames + sceneTiming.theClean.frames}
        fps={FPS}
        width={1080}
        height={1920}
      />

      <Composition
        id="Scene07-Payment"
        component={ScenePaymentPreview}
        durationInFrames={sceneTiming.payment.frames}
        fps={FPS}
        width={1080}
        height={1920}
      />

      <Composition
        id="Scene09-Sponsor"
        component={SceneSponsorPreview}
        durationInFrames={sceneTiming.sponsorSide.frames}
        fps={FPS}
        width={1080}
        height={1920}
      />

      <Composition
        id="Scene12-CTA"
        component={SceneCTAPreview}
        durationInFrames={sceneTiming.ctaWorkers.frames}
        fps={FPS}
        width={1080}
        height={1920}
      />

      <Composition
        id="Scene13-Logo"
        component={SceneLogoPreview}
        durationInFrames={sceneTiming.logoTagline.frames}
        fps={FPS}
        width={1080}
        height={1920}
      />

      {/* ========== SCENE PREVIEWS (Landscape) ========== */}

      <Composition
        id="Scene05-AppDemo-Landscape"
        component={SceneAppDemoLandscape}
        durationInFrames={sceneTiming.problem.frames + sceneTiming.statistics.frames}
        fps={FPS}
        width={1920}
        height={1080}
      />

      <Composition
        id="Scene06-WorkerJourney-Landscape"
        component={SceneWorkerJourneyLandscape}
        durationInFrames={sceneTiming.workerIntro.frames + sceneTiming.theClean.frames}
        fps={FPS}
        width={1920}
        height={1080}
      />

      <Composition
        id="Scene07-Payment-Landscape"
        component={ScenePaymentLandscape}
        durationInFrames={sceneTiming.payment.frames}
        fps={FPS}
        width={1920}
        height={1080}
      />

      <Composition
        id="Scene09-Sponsor-Landscape"
        component={SceneSponsorLandscape}
        durationInFrames={sceneTiming.sponsorSide.frames}
        fps={FPS}
        width={1920}
        height={1080}
      />
    </>
  )
}

export default RemotionRoot
