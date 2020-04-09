/**
 * @license
 * Copyright 2020 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import React from "react";
import {PureComponent, PureComponentProps, PureComponentState} from "../../../../../components";
import {RedocStandalone} from "redoc";


/**
 * Properties
 */
// tslint:disable-next-line:no-empty-interface
export interface ApiDocumentationTabContentProps extends PureComponentProps {
    artifactContent: string;
}

/**
 * State
 */
// tslint:disable-next-line:no-empty-interface
export interface ApiDocumentationTabContentState extends PureComponentState {
    parsedContent: any;
}


/**
 * Models the content of the Artifact Info tab.
 */
export class ApiDocumentationTabContent extends PureComponent<ApiDocumentationTabContentProps, ApiDocumentationTabContentState> {

    constructor(props: Readonly<ApiDocumentationTabContentProps>) {
        super(props);
    }

    public render(): React.ReactElement {
        return (
            <RedocStandalone spec={this.state.parsedContent} />
        );
    }

    protected initializeState(): ApiDocumentationTabContentState {
        return {
            parsedContent: JSON.parse(this.props.artifactContent)
        };
    }
}
