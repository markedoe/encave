// Helper for apple/tools/mac-screenshots.sh (CI only).
//   mac-screens display WIDTH HEIGHT   adds a HiDPI virtual display of WIDTH x HEIGHT
//                                      points (twice as many pixels), kept until killed
//   mac-screens opaque IN.png OUT.png  the same image without its alpha channel, as the
//                                      App Store asks
// Build: clang -fobjc-arc -framework Foundation -framework CoreGraphics
//        -framework ImageIO -framework UniformTypeIdentifiers mac-screens.m -o mac-screens
// CGVirtualDisplay is not public API: its classes are declared here as open-source
// virtual display tools declare them. Never part of the app.
#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>

@interface CGVirtualDisplayDescriptor : NSObject
@property (retain, nonatomic) dispatch_queue_t queue;
@property (retain, nonatomic) NSString *name;
@property (nonatomic) unsigned int maxPixelsHigh;
@property (nonatomic) unsigned int maxPixelsWide;
@property (nonatomic) CGSize sizeInMillimeters;
@property (nonatomic) unsigned int serialNum;
@property (nonatomic) unsigned int productID;
@property (nonatomic) unsigned int vendorID;
@property (copy, nonatomic) void (^terminationHandler)(id, id);
@end

@interface CGVirtualDisplayMode : NSObject
- (instancetype)initWithWidth:(unsigned int)width height:(unsigned int)height refreshRate:(double)refreshRate;
@end

@interface CGVirtualDisplaySettings : NSObject
@property (retain, nonatomic) NSArray *modes;
@property (nonatomic) unsigned int hiDPI;
@end

@interface CGVirtualDisplay : NSObject
@property (readonly, nonatomic) unsigned int displayID;
- (instancetype)initWithDescriptor:(CGVirtualDisplayDescriptor *)descriptor;
- (BOOL)applySettings:(CGVirtualDisplaySettings *)settings;
@end

static void listDisplays(void) {
    CGDirectDisplayID ids[16];
    uint32_t count = 0;
    CGGetActiveDisplayList(16, ids, &count);
    for (uint32_t i = 0; i < count; i++) {
        CGDisplayModeRef mode = CGDisplayCopyDisplayMode(ids[i]);
        CGRect bounds = CGDisplayBounds(ids[i]);
        printf("display %u%s: %.0fx%.0f points at %.0f,%.0f, %zux%zu pixels\n", ids[i],
               CGDisplayIsMain(ids[i]) ? " (main)" : "", bounds.size.width, bounds.size.height,
               bounds.origin.x, bounds.origin.y,
               mode ? CGDisplayModeGetPixelWidth(mode) : 0, mode ? CGDisplayModeGetPixelHeight(mode) : 0);
        CGDisplayModeRelease(mode);
    }
    fflush(stdout);
}

static int addDisplay(unsigned int width, unsigned int height) {
    CGVirtualDisplayDescriptor *descriptor = [[CGVirtualDisplayDescriptor alloc] init];
    descriptor.queue = dispatch_get_main_queue();
    descriptor.name = @"Screenshots";
    descriptor.maxPixelsWide = width * 2;
    descriptor.maxPixelsHigh = height * 2;
    // About 220 pixels per inch, like a Retina MacBook screen.
    descriptor.sizeInMillimeters = CGSizeMake(width * 2 * 25.4 / 220, height * 2 * 25.4 / 220);
    descriptor.vendorID = 0xEEEE;
    descriptor.productID = 0x1234;
    descriptor.serialNum = 1;
    descriptor.terminationHandler = ^(id a, id b) { fprintf(stderr, "virtual display ended\n"); };
    CGVirtualDisplay *display = [[CGVirtualDisplay alloc] initWithDescriptor:descriptor];
    if (!display) {
        fprintf(stderr, "could not add a virtual display\n");
        return 1;
    }
    CGVirtualDisplaySettings *settings = [[CGVirtualDisplaySettings alloc] init];
    settings.hiDPI = 1;
    settings.modes = @[
        [[CGVirtualDisplayMode alloc] initWithWidth:width * 2 height:height * 2 refreshRate:60],
        [[CGVirtualDisplayMode alloc] initWithWidth:width height:height refreshRate:60],
    ];
    if (![display applySettings:settings]) {
        fprintf(stderr, "could not set the virtual display mode\n");
        return 1;
    }
    CGDirectDisplayID displayID = display.displayID;
    // Pick the HiDPI mode: WIDTH x HEIGHT points, twice as many pixels.
    CFDictionaryRef options = (__bridge CFDictionaryRef) @{ (__bridge NSString *)kCGDisplayShowDuplicateLowResolutionModes: @YES };
    CFArrayRef modes = CGDisplayCopyAllDisplayModes(displayID, options);
    for (CFIndex i = 0; modes && i < CFArrayGetCount(modes); i++) {
        CGDisplayModeRef mode = (CGDisplayModeRef)CFArrayGetValueAtIndex(modes, i);
        if (CGDisplayModeGetWidth(mode) == width && CGDisplayModeGetPixelWidth(mode) == width * 2) {
            CGDisplayConfigRef config;
            CGBeginDisplayConfiguration(&config);
            CGConfigureDisplayWithDisplayMode(config, displayID, mode, NULL);
            CGCompleteDisplayConfiguration(config, kCGConfigureForSession);
            break;
        }
    }
    if (modes) CFRelease(modes);
    sleep(2);
    listDisplays();
    printf("ready\n");
    fflush(stdout);
    // The display lasts as long as this process.
    dispatch_main();
}

static int makeOpaque(const char *input, const char *output) {
    NSURL *inURL = [NSURL fileURLWithPath:@(input)], *outURL = [NSURL fileURLWithPath:@(output)];
    CGImageSourceRef source = CGImageSourceCreateWithURL((__bridge CFURLRef)inURL, NULL);
    CGImageRef image = source ? CGImageSourceCreateImageAtIndex(source, 0, NULL) : NULL;
    if (!image) { fprintf(stderr, "cannot read %s\n", input); return 1; }
    size_t width = CGImageGetWidth(image), height = CGImageGetHeight(image);
    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CGContextRef context = CGBitmapContextCreate(NULL, width, height, 8, 0, space, (CGBitmapInfo)kCGImageAlphaNoneSkipLast);
    CGContextSetRGBFillColor(context, 1, 1, 1, 1);
    CGContextFillRect(context, CGRectMake(0, 0, width, height));
    CGContextDrawImage(context, CGRectMake(0, 0, width, height), image);
    CGImageRef flat = CGBitmapContextCreateImage(context);
    CGImageDestinationRef destination = CGImageDestinationCreateWithURL(
        (__bridge CFURLRef)outURL, (__bridge CFStringRef)UTTypePNG.identifier, 1, NULL);
    CGImageDestinationAddImage(destination, flat, NULL);
    BOOL ok = CGImageDestinationFinalize(destination);
    CFRelease(destination); CGImageRelease(flat); CGContextRelease(context);
    CGColorSpaceRelease(space); CGImageRelease(image); CFRelease(source);
    return ok ? 0 : 1;
}

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc == 4 && strcmp(argv[1], "display") == 0) return addDisplay(atoi(argv[2]), atoi(argv[3]));
        if (argc == 4 && strcmp(argv[1], "opaque") == 0) return makeOpaque(argv[2], argv[3]);
        if (argc == 2 && strcmp(argv[1], "list") == 0) { listDisplays(); return 0; }
        fprintf(stderr, "usage: mac-screens display WIDTH HEIGHT | opaque IN OUT | list\n");
        return 2;
    }
}
