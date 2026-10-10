#import <Foundation/Foundation.h>
#import <AVFAudio/AVFAudio.h>

NS_ASSUME_NONNULL_BEGIN

/// Apple's audio engine reports a wiring it doesn't accept by throwing an
/// Objective-C exception, which closes the app — neither Kotlin nor Swift
/// can catch one. These do the risky calls inside @try and hand back the
/// reason instead (nil = it worked).
@interface StellarTry : NSObject

+ (nullable NSString *)connectIn:(AVAudioEngine *)engine
                            from:(AVAudioNode *)from
                              to:(AVAudioNode *)to
                          format:(nullable AVAudioFormat *)format
    NS_SWIFT_NAME(connect(in:from:to:format:));

+ (nullable NSString *)startEngine:(AVAudioEngine *)engine
    NS_SWIFT_NAME(start(engine:));

@end

NS_ASSUME_NONNULL_END
