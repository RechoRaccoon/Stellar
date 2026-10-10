#import "StellarTry.h"

@implementation StellarTry

+ (nullable NSString *)connectIn:(AVAudioEngine *)engine
                            from:(AVAudioNode *)from
                              to:(AVAudioNode *)to
                          format:(nullable AVAudioFormat *)format {
    @try {
        [engine connect:from to:to format:format];
        return nil;
    } @catch (NSException *e) {
        return e.reason ?: e.name ?: @"The audio engine refused the connection";
    }
}

+ (nullable NSString *)startEngine:(AVAudioEngine *)engine {
    @try {
        [engine prepare];
        NSError *error = nil;
        if (![engine startAndReturnError:&error]) {
            return error.localizedDescription ?: @"The audio engine didn't start";
        }
        return nil;
    } @catch (NSException *e) {
        return e.reason ?: e.name ?: @"The audio engine didn't start";
    }
}

@end
